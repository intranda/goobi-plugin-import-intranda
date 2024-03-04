package de.intranda.goobi.plugins;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.io.FileUtils;
import org.goobi.beans.Process;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.DocstructElement;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.interfaces.IImportPluginVersion2;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.properties.ImportProperty;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.forms.MassImportForm;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.UghHelper;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j
public class GoobiProcessFolderImport implements IImportPluginVersion2, IPlugin {

    private static final String PLUGIN_TITLE = "intranda_Goobi_Process_Folder_Import";

    private Prefs prefs;
    private String importFolder;
    private MassImportForm form;

    private String prefixInSource = "";
    private String suffixInSource = "";
    private String processTitleInSource = "";

    private String prefixInDestination;
    private String suffixInDestination;
    private String processTitleDestination;

    private String currentMetsFile;

    private boolean moveFiles = false;
    private boolean replaceExisting = false;

    private Map<String, String> processTitleGeneration = new HashMap<>();

    public GoobiProcessFolderImport() {

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
        replaceExisting = config.getBoolean("replaceExistingProcesses", false);
    }

    public static void main(String[] args) throws Exception {
        GoobiProcessFolderImport imp = new GoobiProcessFolderImport();
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
        Fileformat ff = null;
        try {
            ff = new MetsMods(prefs);
            ff.read(currentMetsFile);

            DocStruct topstruct = ff.getDigitalDocument().getLogicalDocStruct();
            DocStruct anchor = null;
            if (topstruct.getType().isAnchor()) {
                anchor = topstruct;
                topstruct = anchor.getAllChildren().get(0);
            }
            processTitleDestination = processTitleGeneration.get(topstruct.getType().getName());

            List<Metadata> metadataList = topstruct.getAllMetadata();
            String title = "";
            String author = "";
            for (Metadata metadata : metadataList) {
                processTitleDestination = processTitleDestination.replace(metadata.getType().getName(), metadata.getValue());
                if (metadata.getType().getName().equals("TitleDocMain")) {
                    title = metadata.getValue();
                }
            }
            List<Person> personList = topstruct.getAllPersons();
            if (personList != null && !personList.isEmpty()) {
                for (Person person : personList) {
                    if (person.getType().getName().equals("Author")) {
                        author = person.getLastname();
                    }
                }
            }

            // replace ATS with ATS from source or create new one?

            String ats = createAtstsl(title, author);
            processTitleDestination = processTitleDestination.replace("ATS", ats);

            UghHelper.convertUmlaut(processTitleDestination);
            processTitleDestination = processTitleDestination.replaceAll("[\\W]", "");

            ff.write(getImportFolder() + processTitleDestination + ".xml");
        } catch (UGHException e) {
            log.error(e);
        }

        return ff;
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
            if (!Files.exists(Paths.get(currentMetsFile))) {
                continue;
            }
            String imagesFolder = currentRecord.getData() + File.separator + "images";

            List<String> imageFolderList = StorageProvider.getInstance().list(imagesFolder, NIOFileUtils.folderFilter);
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
            processTitleInSource = masterFolder.replace(prefixInSource, "").replace(suffixInSource, "");
            ImportObject io = new ImportObject();
            Fileformat fileFormat = null;
            try {
                fileFormat = convertData();
            } catch (ImportPluginException e) {
                log.error(e);
                io.setImportReturnValue(ImportReturnValue.InvalidData);
                io.setErrorMessage(e.getMessage());
            }
            // check if the process exists
            if (replaceExisting) {
                boolean dataReplaced = false;
                Process existingProcess = ProcessManager.getProcessByExactTitle(processTitleDestination);
                if (existingProcess != null) {
                    try {
                        existingProcess.writeMetadataFile(fileFormat);
                        dataReplaced = true;
                    } catch (WriteException | PreferencesException | IOException | SwapException e) {
                        log.error(e);
                    }

                    Path sourceRootFolder = Paths.get(currentRecord.getData());
                    moveImageIntoProcessFolder(existingProcess, sourceRootFolder);
                }
                if (dataReplaced) {
                    // TODO delete mets file, anchor file, image folder
                    continue;
                }
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

            generatedFiles.add(io);
            io.setMetsFilename(getImportFolder() + processTitleDestination + ".xml");
            io.setProcessTitle(processTitleDestination);

        }

        return generatedFiles;
    }

    private void moveImageIntoProcessFolder(Process existingProcess, Path sourceRootFolder) {
        if (StorageProvider.getInstance().isFileExists(sourceRootFolder)) {
            Path sourceImageFolder = Paths.get(sourceRootFolder.toString(), "images");
            Path sourceOcrFolder = Paths.get(sourceRootFolder.toString(), "ocr");
            if (StorageProvider.getInstance().isDirectory(sourceImageFolder)) {
                List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceImageFolder.toString());

                for (Path currentData : dataInSourceImageFolder) {
                    if (Files.isDirectory(currentData)) {
                        try {
                            FileUtils.copyDirectory(currentData.toFile(), Paths.get(existingProcess.getImagesDirectory()).toFile());
                        } catch (IOException | SwapException e) {
                            log.error(e);
                        }
                    } else {
                        try {
                            FileUtils.copyFile(currentData.toFile(),
                                    Paths.get(existingProcess.getImagesDirectory(), currentData.getFileName().toString()).toFile());
                        } catch (IOException | SwapException e) {
                            log.error(e);
                        }
                    }
                }
            }

            // ocr
            if (Files.exists(sourceOcrFolder)) {
                List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceOcrFolder.toString());
                for (Path currentData : dataInSourceImageFolder) {
                    if (Files.isRegularFile(currentData)) {
                        try {
                            copyFile(currentData, Paths.get(existingProcess.getOcrDirectory(), currentData.getFileName().toString()));
                        } catch (IOException | SwapException e) {
                            log.error(e);
                        }
                    } else {
                        try {
                            FileUtils.copyDirectory(currentData.toFile(), Paths.get(existingProcess.getOcrDirectory()).toFile());
                        } catch (IOException | SwapException e) {
                            log.error(e);
                        }
                    }
                }
            }
        }
    }

    private void moveSourceData(String source) throws IOException {
        Path destinationRootFolder = Paths.get(importFolder, getProcessTitle());
        Path destinationImagesFolder = Paths.get(destinationRootFolder.toString(), "images");
        Path destinationOcrFolder = Paths.get(destinationRootFolder.toString(), "ocr");

        Path sourceRootFolder = Paths.get(source);
        Path sourceImageFolder = Paths.get(sourceRootFolder.toString(), "images");
        Path sourceOcrFolder = Paths.get(sourceRootFolder.toString(), "ocr");

        if (!Files.exists(destinationRootFolder)) {
            try {
                Files.createDirectories(destinationRootFolder);
            } catch (IOException e) {
                log.error(e);
            }
        }
        //        // copy files from source
        //        List<Path> fileList = NIOFileUtils.listFiles(sourceRootFolder.toString(), NIOFileUtils.fileFilter);
        //        for (Path file : fileList) {
        //            copyFile(file, Paths.get(destinationRootFolder.toString(), file.getFileName().toString()));
        //        }

        // images
        if (Files.exists(sourceImageFolder)) {
            if (!Files.exists(destinationImagesFolder)) {
                try {
                    Files.createDirectories(destinationImagesFolder);
                } catch (IOException e) {
                    log.error(e);
                }
            }
            List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceImageFolder.toString());

            for (Path currentData : dataInSourceImageFolder) {
                if (Files.isDirectory(currentData)) {
                    try {
                        copyFolder(currentData, destinationImagesFolder);
                    } catch (IOException e) {
                        log.error(e);
                        throw e;
                    }
                } else {
                    try {
                        copyFile(currentData, Paths.get(destinationImagesFolder.toString(), currentData.getFileName().toString()));
                    } catch (IOException e) {
                        log.error(e);
                        throw e;
                    }
                }
            }
        }

        // ocr
        if (Files.exists(sourceOcrFolder)) {
            if (!Files.exists(destinationOcrFolder)) {
                Files.createDirectories(destinationOcrFolder);
            }
            List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceOcrFolder.toString());

            for (Path currentData : dataInSourceImageFolder) {
                if (Files.isRegularFile(currentData)) {
                    copyFile(currentData, Paths.get(destinationOcrFolder.toString(), currentData.getFileName().toString()));
                } else {
                    copyFolder(currentData, destinationOcrFolder);
                }
            }
        }
        // copy imageData.xml
        Path copyDataFile = Paths.get(source, "imageData.xml");
        if (Files.exists(copyDataFile)) {
            Path destinationDataFile = Paths.get(destinationRootFolder.toString(), "imageData.xml");
            copyFile(copyDataFile, destinationDataFile);

        }
        // copy taskmanager/issues_result.json
        copyDataFile = Paths.get(source, "taskmanager/issues_result.json");
        if (Files.exists(copyDataFile)) {
            Path destinationDataFile = Paths.get(destinationRootFolder.toString(), "taskmanager/issues_result.json");
            Files.createDirectories(destinationDataFile.getParent());
            copyFile(copyDataFile, destinationDataFile);

        }
        // copy taskmanager/issues_result_manual.json
        copyDataFile = Paths.get(source, "taskmanager/issues_result_manual.json");
        if (Files.exists(copyDataFile)) {
            Path destinationDataFile = Paths.get(destinationRootFolder.toString(), "taskmanager/issues_result_manual.json");
            Files.createDirectories(destinationDataFile.getParent());
            copyFile(copyDataFile, destinationDataFile);

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
        if (!Files.exists(destinationSubFolder)) {
            Files.createDirectories(destinationSubFolder);
        }

        if (moveFiles) {
            Files.move(currentData, destinationSubFolder, StandardCopyOption.REPLACE_EXISTING);
        } else {
            List<Path> files = StorageProvider.getInstance().listFiles(currentData.toString());
            for (Path p : files) {
                copyFile(p, Paths.get(destinationSubFolder.toString(), p.getFileName().toString()));
            }
            //            NIOFileUtils.copyDirectory(currentData, destinationSubFolder);
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
            if (Files.exists(Paths.get(folder, filename, "meta.xml"))) {
                Record record = new Record();
                record.setId(folder + filename);
                record.setData(folder + filename);
                answer.add(record);
            }
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
        List<ImportType> answer = new ArrayList<>();
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
        List<String> filesInImportFolder = StorageProvider.getInstance().list(folder);
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

    public String createAtstsl(String myTitle, String autor) {
        String myAtsTsl = "";
        if (autor != null && !autor.equals("")) {
            /* autor */
            if (autor.length() > 4) {
                myAtsTsl = autor.substring(0, 4);
            } else {
                myAtsTsl = autor;
                /* titel */
            }

            if (myTitle.length() > 4) {
                myAtsTsl += myTitle.substring(0, 4);
            } else {
                myAtsTsl += myTitle;
            }
        }

        /*
         * -------------------------------- bei Zeitschriften Tsl berechnen --------------------------------
         */
        // if (gattung.startsWith("ab") || gattung.startsWith("ob")) {
        if (autor == null || autor.equals("")) {
            myAtsTsl = "";
            StringTokenizer tokenizer = new StringTokenizer(myTitle);
            int counter = 1;
            while (tokenizer.hasMoreTokens()) {
                String tok = tokenizer.nextToken();
                if (counter == 1) {
                    if (tok.length() > 4) {
                        myAtsTsl += tok.substring(0, 4);
                    } else {
                        myAtsTsl += tok;
                    }
                }
                if (counter == 2 || counter == 3) {
                    if (tok.length() > 2) {
                        myAtsTsl += tok.substring(0, 2);
                    } else {
                        myAtsTsl += tok;
                    }
                }
                if (counter == 4) {
                    if (tok.length() > 1) {
                        myAtsTsl += tok.substring(0, 1);
                    } else {
                        myAtsTsl += tok;
                    }
                }
                counter++;
            }
        }
        /* im ATS-TSL die Umlaute ersetzen */
        myAtsTsl = convertUmlaut(myAtsTsl);
        myAtsTsl = myAtsTsl.toLowerCase().replaceAll("[\\W]", "");
        return myAtsTsl;
    }

    protected String convertUmlaut(String inString) {
        /* Pfad zur Datei ermitteln */
        String filename = ConfigurationHelper.getInstance().getConfigurationFolder() + "goobi_opacUmlaut.txt";
        //      }
        /* Datei zeilenweise durchlaufen und die Sprache vergleichen */
        try {
            FileInputStream fis = new FileInputStream(filename);
            InputStreamReader isr = new InputStreamReader(fis, "UTF8");
            BufferedReader in = new BufferedReader(isr);
            String str;
            while ((str = in.readLine()) != null) {
                if (str.length() > 0) {
                    inString = inString.replaceAll(str.split(" ")[0], str.split(" ")[1]);
                }
            }
            in.close();
        } catch (IOException e) {
            log.error("IOException bei Umlautkonvertierung", e);
        }
        return inString;
    }

    @Override
    public boolean isRunnableAsGoobiScript() {
        return true;
    }
}
