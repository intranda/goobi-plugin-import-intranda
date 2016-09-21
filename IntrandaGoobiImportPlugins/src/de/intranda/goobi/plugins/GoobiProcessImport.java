package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.DocstructElement;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.interfaces.IImportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.properties.ImportProperty;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.forms.MassImportForm;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.UghHelper;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j
public class GoobiProcessImport implements IImportPlugin, IPlugin {

    private static final String PLUGIN_TITLE = "intranda_Goobi_Process_Folder_Import";

    private Prefs prefs;
    private String importFolder;
    private MassImportForm form;

    private String prefixInSource;
    private String suffixInSource;
    private String processTitleInSource;

    private String prefixInDestination;
    private String suffixInDestination;
    private String processTitleDestination;

    private String currentMetsFile;

    private boolean moveFiles = false;

    private Map<String, String> processTitleGeneration = new HashMap<>();

    public GoobiProcessImport() {

        XMLConfiguration config = ConfigPlugins.getPluginConfig(this);

        config.setExpressionEngine(new XPathExpressionEngine());
        prefixInDestination = config.getString("prefixInDestination", "orig_");
        suffixInDestination = config.getString("suffixInDestination", "_tif");

        List<HierarchicalConfiguration> configuration = config.configurationsAt("title/doctype");
        for (HierarchicalConfiguration currentItem : configuration) {
            String doctype = currentItem.getString("@doctypename");
            String processtitle = currentItem.getString("@processtitle");
            processTitleGeneration.put(doctype, processtitle);
        }

        moveFiles = config.getBoolean("moveFiles", false);
    }

    public static void main(String[] args) throws Exception {
        GoobiProcessImport imp = new GoobiProcessImport();
        imp.setImportFolder("/opt/digiverso/goobi/tmp/");
        Record fixture = new Record();
        fixture.setData("/opt/digiverso/intrandaTransfer/38646");
        fixture.setId("38646");
        List<Record> testList = new ArrayList<>();
        testList.add(fixture);

        Prefs prefs = new Prefs();
        prefs.loadPrefs("/opt/digiverso/goobi/rulesets/newspaper.xml");
        imp.setPrefs(prefs);

        imp.generateFiles(testList);

        System.out.println(imp.getProcessTitle());
    }

    @Override
    public PluginType getType() {
        return PluginType.Import;
    }

    @Override
    public String getTitle() {
        return PLUGIN_TITLE;
    }

    @Override
    public void setPrefs(Prefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public void setData(Record r) {
    }

    @Override
    public Fileformat convertData() throws ImportPluginException {

        try {
            Fileformat ff = new MetsMods(prefs);
            ff.read(currentMetsFile);

            DocStruct topstruct = ff.getDigitalDocument().getLogicalDocStruct();
            DocStruct anchor = null;
            if (topstruct.getType().isAnchor()) {
                anchor = topstruct;
                topstruct = anchor.getAllChildren().get(0);
            }
            processTitleDestination = processTitleGeneration.get(topstruct.getType().getName());

            List<Metadata> metadataList = topstruct.getAllMetadata();
            for (Metadata metadata : metadataList) {
                processTitleDestination = processTitleDestination.replace(metadata.getType().getName(), metadata.getValue());
            }

            // replace ATS with ATS from source or create new one?
            processTitleDestination = processTitleDestination.replace("ATS", processTitleInSource.substring(0, processTitleInSource.indexOf("_")));

            UghHelper.convertUmlaut(processTitleDestination);
            processTitleDestination = processTitleDestination.replaceAll("[\\W]", "");

            ff.write(getImportFolder() + processTitleDestination + ".xml");
        } catch (UGHException e) {
            log.error(e);
        }

        return null;
    }

    @Override
    public String getImportFolder() {
        return this.importFolder;
    }

    @Override
    public String getProcessTitle() {
        return processTitleDestination;
    }

    @Override
    public List<ImportObject> generateFiles(List<Record> recordsToImport) {

        List<ImportObject> generatedFiles = new ArrayList<>(recordsToImport.size());

        for (Record currentRecord : recordsToImport) {
            form.addProcessToProgressBar();

            currentMetsFile = currentRecord.getData() + File.separator + "meta.xml";

            String imagesFolder = currentRecord.getData() + File.separator + "images";

            List<String> imageFolderList = NIOFileUtils.list(imagesFolder, NIOFileUtils.folderFilter);
            String masterFolder = "";
            for (String currentImageFolder : imageFolderList) {
                if (currentImageFolder.startsWith("master_")) {
                    prefixInSource = "master_";
                    suffixInSource = "_media";
                    masterFolder = currentImageFolder;
                } else if (currentImageFolder.startsWith("orig_")) {
                    prefixInSource = "orig_";
                    suffixInSource = "_tif";
                    masterFolder = currentImageFolder;
                }
            }
            processTitleInSource = masterFolder.replace(prefixInSource, "").replaceAll(suffixInSource, "");
            ImportObject io = new ImportObject();
            generatedFiles.add(io);
            try {
                convertData();
            } catch (ImportPluginException e) {
                log.error(e);
                io.setImportReturnValue(ImportReturnValue.InvalidData);
                io.setErrorMessage(e.getMessage());
            }

            // copy all files with new folder names

            try {
                moveSourceData(currentRecord.getData());
            } catch (IOException e) {
                log.error(e);
                io.setImportReturnValue(ImportReturnValue.WriteError);
                io.setErrorMessage(e.getMessage());
            }
//            io.setImportFileName(processTitleDestination);
            io.setMetsFilename(getImportFolder() + processTitleDestination + ".xml");
            io.setProcessTitle(processTitleDestination);
            
        }

        return generatedFiles;
    }

    private void moveSourceData(String source) throws IOException {
        Path destinationRootFolder = Paths.get(importFolder, getProcessTitle());
        Path destinationImagesFolder = Paths.get(destinationRootFolder.toString(), "images");
        Path destinationOcrFolder = Paths.get(destinationRootFolder.toString(), "ocr");

        Path sourceRootFolder = Paths.get(source);
        Path sourceImageFolder = Paths.get(sourceRootFolder.toString(), "images");
        Path sourceOcrFolder = Paths.get(sourceRootFolder.toString(), "ocr");

        if (!Files.exists(destinationRootFolder)) {
            Files.createDirectories(destinationRootFolder);
        }
        //        // copy files from source
        //        List<Path> fileList = NIOFileUtils.listFiles(sourceRootFolder.toString(), NIOFileUtils.fileFilter);
        //        for (Path file : fileList) {
        //            copyFile(file, Paths.get(destinationRootFolder.toString(), file.getFileName().toString()));
        //        }

        // images
        if (Files.exists(sourceImageFolder)) {
            if (!Files.exists(destinationImagesFolder)) {
                Files.createDirectories(destinationImagesFolder);
            }
            List<Path> dataInSourceImageFolder = NIOFileUtils.listFiles(sourceImageFolder.toString());

            for (Path currentData : dataInSourceImageFolder) {
                if (Files.isRegularFile(currentData)) {
                    copyFile(currentData, Paths.get(destinationImagesFolder.toString(), currentData.getFileName().toString()));
                } else {
                    copyFolder(currentData, destinationImagesFolder);
                }
            }
        }

        // ocr
        if (Files.exists(sourceOcrFolder)) {
            if (!Files.exists(destinationOcrFolder)) {
                Files.createDirectories(destinationOcrFolder);
            }
            List<Path> dataInSourceImageFolder = NIOFileUtils.listFiles(sourceOcrFolder.toString());

            for (Path currentData : dataInSourceImageFolder) {
                if (Files.isRegularFile(currentData)) {
                    copyFile(currentData, Paths.get(destinationOcrFolder.toString(), currentData.getFileName().toString()));
                } else {
                    copyFolder(currentData, destinationOcrFolder);
                }
            }
        }

    }

    private void copyFolder(Path currentData, Path destinationFolder) throws IOException {
        Path destinationSubFolder;

        if (currentData.getFileName().toString().equals(prefixInSource + processTitleInSource + suffixInSource)) {
            destinationSubFolder = Paths.get(destinationFolder.toString(), prefixInDestination + processTitleDestination + suffixInDestination);

        } else if (currentData.getFileName().toString().equals(processTitleInSource + suffixInSource)) {
            destinationSubFolder = Paths.get(destinationFolder.toString(), processTitleDestination + suffixInDestination);
        } else {
            // get suffix
            String foldername = currentData.getFileName().toString();
            if (foldername.startsWith(processTitleInSource) && foldername.contains("_")) {
                String suffix = foldername.substring(foldername.lastIndexOf("_"));
                destinationSubFolder = Paths.get(destinationFolder.toString(), processTitleDestination + suffix);
            } else {
                destinationSubFolder = Paths.get(destinationFolder.toString(), foldername);
            }
        }

        if (moveFiles) {
            Files.move(currentData, destinationSubFolder, StandardCopyOption.REPLACE_EXISTING);
        } else {
            NIOFileUtils.copyDirectory(currentData, destinationSubFolder);
        }

    }

    private void copyFile(Path file, Path destination) throws IOException {

        if (moveFiles) {
            Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
        }

    }

    @Override
    public void setForm(MassImportForm form) {
        this.form = form;
    }

    @Override
    public void setImportFolder(String folder) {
        this.importFolder = folder;
    }

    @Override
    public List<Record> splitRecords(String records) {
        return null;
    }

    @Override
    public List<Record> generateRecordsFromFile() {
        return null;
    }

    @Override
    public List<Record> generateRecordsFromFilenames(List<String> filenames) {
        List<Record> answer = new LinkedList<>();
        String folder = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/goobi/import/");
        for (String filename : filenames) {
            Record record = new Record();
            record.setId(filename);
            record.setData(folder + filename);
            answer.add(record);
        }
        return answer;
    }

    @Override
    public void setFile(File importFile) {
    }

    @Override
    public List<String> splitIds(String ids) {
        return null;
    }

    @Override
    public List<ImportType> getImportTypes() {
        List<ImportType> answer = new ArrayList<ImportType>();
        answer.add(ImportType.FOLDER);
        return answer;
    }

    @Override
    public List<ImportProperty> getProperties() {
        return null;
    }

    @Override
    public List<String> getAllFilenames() {
        String folder = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/goobi/import/");
        List<String> filesInImportFolder = NIOFileUtils.list(folder);
        return filesInImportFolder;
    }

    @Override
    public void deleteFiles(List<String> selectedFilenames) {
    }

    @Override
    public List<? extends DocstructElement> getCurrentDocStructs() {
        return null;
    }

    @Override
    public String deleteDocstruct() {
        return null;
    }

    @Override
    public String addDocstruct() {
        return null;
    }

    @Override
    public List<String> getPossibleDocstructs() {
        return null;
    }

    @Override
    public DocstructElement getDocstruct() {
        return null;
    }

    @Override
    public void setDocstruct(DocstructElement dse) {
    }
    
    public String getDescription() {
        return "";
    }
}
