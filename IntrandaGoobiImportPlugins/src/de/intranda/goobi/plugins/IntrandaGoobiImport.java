package de.intranda.goobi.plugins;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

import net.xeoh.plugins.base.annotations.PluginImplementation;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.goobi.production.importer.DocstructElement;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IImportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.properties.ImportProperty;
import org.jdom.Document;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

import org.goobi.beans.Processproperty;
import org.goobi.beans.Templateproperty;
import org.goobi.beans.Masterpieceproperty;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.forms.MassImportForm;
import de.sub.goobi.helper.UghHelper;
import de.sub.goobi.helper.exceptions.ImportPluginException;

@PluginImplementation
public class IntrandaGoobiImport implements IImportPlugin, IPlugin {

    protected static final Logger logger = Logger.getLogger(IntrandaGoobiImport.class);

    private static final String NAME = "goobiImport";

    private static String FOLDER_PREFIX = "orig_";

    private static String FOLDER_SUFFIX = "_tif";
    //	private static final String FOLDER_EXTENSION = "_tif";

    protected String data = "";
    protected String importFolder = "";
    protected File importFile;
    protected Prefs prefs;
    protected String currentIdentifier;
    protected String identifierAnalog = null;
    protected String identifierAnalogVolume = null;

    protected List<String> currentCollectionList;
    protected String imagefolder;
    protected String ats;
    protected List<Processproperty> processProperties = new ArrayList<Processproperty>();
    protected List<Templateproperty> templateProperties = new ArrayList<Templateproperty>();
    protected List<Masterpieceproperty> workProperties = new ArrayList<Masterpieceproperty>();

    protected String currentTitle;
    protected String docType;
    protected String author = "";
    protected String volumeNumber = "";
    private MassImportForm form;

    
    @Override
    public PluginType getType() {
        return PluginType.Import;
    }

    @Override
    public String getTitle() {
        return NAME;
    }

    public String getId() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return NAME;
    }

    @Override
    public void setPrefs(Prefs prefs) {
        this.prefs = prefs;

        FOLDER_PREFIX = ConfigPlugins.getPluginConfig(this).getString("folderprefix", "orig_");
        FOLDER_SUFFIX = ConfigPlugins.getPluginConfig(this).getString("foldersuffix", "_tif");

    }

    @Override
    public void setData(Record r) {
        this.data = r.getData();
    }

    @Override
    public Fileformat convertData() throws ImportPluginException {
        identifierAnalog = null;
        identifierAnalogVolume = null;
        volumeNumber = null;
        Fileformat ff = null;
        try {
            ff = new MetsMods(prefs);
            ff.read(data);

        } catch (PreferencesException e) {
            logger.error(e.getMessage(), e);
            throw new ImportPluginException("Cannot read prefererences file", e);
        } catch (ReadException e) {
            logger.error(e.getMessage(), e);
            throw new ImportPluginException("Cannot read mets file " + data, e);
        }
        if (ff != null) {

            DigitalDocument dd = null;
            DocStruct logicalDS = null;
            try {
                dd = ff.getDigitalDocument();
                logicalDS = dd.getLogicalDocStruct();
                DocStruct child = null;
                if (logicalDS.getType().isAnchor()) {
                    child = logicalDS.getAllChildren().get(0);
                }
                // reading title
                MetadataType titleType = prefs.getMetadataTypeByName("TitleDocMain");
                List<? extends Metadata> mdList = logicalDS.getAllMetadataByType(titleType);
                if (mdList != null && mdList.size() > 0) {
                    Metadata title = mdList.get(0);
                    currentTitle = title.getValue();

                }
                // reading identifier
                MetadataType identifierType = prefs.getMetadataTypeByName("CatalogIDDigital");
                mdList = logicalDS.getAllMetadataByType(identifierType);
                if (mdList != null && mdList.size() > 0) {
                    Metadata identifier = mdList.get(0);
                    currentIdentifier = identifier.getValue();
                } else {
                    currentIdentifier = String.valueOf(System.currentTimeMillis());
                }

                try {
                    MetadataType identifierAnalogType = prefs.getMetadataTypeByName("CatalogIDSource");
                    List<? extends Metadata> list = logicalDS.getAllMetadataByType(identifierAnalogType);
                    if (list != null && mdList.size() > 0) {
                        Metadata identifier = list.get(0);
                        identifierAnalog = identifier.getValue();
                    }
                } catch (Exception e) {

                }
                // reading author

                MetadataType authorType = prefs.getMetadataTypeByName("Author");
                List<Person> personList = logicalDS.getAllPersonsByType(authorType);
                if (personList != null && personList.size() > 0) {
                    Person authorMetadata = personList.get(0);
                    author = authorMetadata.getDisplayname();

                }

                // reading volume number
                if (child != null) {
                    MetadataType mdt = prefs.getMetadataTypeByName("CurrentNoSorting");
                    mdList = child.getAllMetadataByType(mdt);
                    if (mdList != null && mdList.size() > 0) {
                        Metadata md = mdList.get(0);
                        volumeNumber = md.getValue();
                    } else {
                        mdt = prefs.getMetadataTypeByName("DateIssuedSort");
                        mdList = child.getAllMetadataByType(mdt);
                        if (mdList != null && mdList.size() > 0) {
                            Metadata md = mdList.get(0);
                            volumeNumber = md.getValue();
                        }
                    }

                    MetadataType identifierAnalogType = prefs.getMetadataTypeByName("CatalogIDSource");
                    List<? extends Metadata> list = child.getAllMetadataByType(identifierAnalogType);
                    if (list != null && list.size() > 0) {
                        Metadata identifier = list.get(0);
                        identifierAnalogVolume = identifier.getValue();
                    }

                }

                // reading ats
                MetadataType atsType = prefs.getMetadataTypeByName("TSL_ATS");
                mdList = logicalDS.getAllMetadataByType(atsType);
                if (mdList != null && mdList.size() > 0) {
                    Metadata atstsl = mdList.get(0);
                    ats = atstsl.getValue();
                } else {
                    // generating ats
                    ats = createAtstsl(currentTitle, author);
                }

                {
                    Templateproperty prop = new Templateproperty();
                    prop.setTitel("Titel");
                    prop.setWert(currentTitle);
                    templateProperties.add(prop);
                }
                {
                    if (StringUtils.isNotBlank(volumeNumber)) {
                        Templateproperty prop = new Templateproperty();
                        prop.setTitel("Bandnummer");
                        prop.setWert(volumeNumber);
                        templateProperties.add(prop);
                    }
                }
                {
                    MetadataType identifierAnalogType = prefs.getMetadataTypeByName("CatalogIDSource");
                    mdList = logicalDS.getAllMetadataByType(identifierAnalogType);
                    if (mdList != null && mdList.size() > 0) {
                        String analog = mdList.get(0).getValue();

                        Templateproperty prop = new Templateproperty();
                        prop.setTitel("Identifier");
                        prop.setWert(analog);
                        templateProperties.add(prop);

                    }
                }

                {
                    if (child != null) {
                        mdList = child.getAllMetadataByType(identifierType);
                        if (mdList != null && mdList.size() > 0) {
                            Metadata identifier = mdList.get(0);
                            Masterpieceproperty prop = new Masterpieceproperty();
                            prop.setTitel("Identifier Band");
                            prop.setWert(identifier.getValue());
                            workProperties.add(prop);
                        }

                    }
                }
                {
                    Masterpieceproperty prop = new Masterpieceproperty();
                    prop.setTitel("Artist");
                    prop.setWert(author);
                    workProperties.add(prop);
                }
                // {
                // docType = logicalDS.getType().getName();
                // Werkstueckeigenschaft prop = new Werkstueckeigenschaft();
                // prop.setTitel("DocType");
                // prop.setWert(docType);
                // workProperties.add(prop);
                // }
                {
                    Masterpieceproperty prop = new Masterpieceproperty();
                    prop.setTitel("ATS");
                    prop.setWert(ats);
                    workProperties.add(prop);
                }
                {
                    Masterpieceproperty prop = new Masterpieceproperty();
                    prop.setTitel("Identifier");
                    prop.setWert(currentIdentifier);
                    workProperties.add(prop);
                }

                // {
                // Werkstueckeigenschaft prop = new Werkstueckeigenschaft();
                // prop.setTitel("TifHeaderDocumentname");
                // prop.setWert(getProcessTitle());
                // workProperties.add(prop);
                // }
                // {
                // String description = "|<DOC_TYPE>" + docType + "|<title>" + currentTitle + "|<author>" + author + "|<STRCT>" + getProcessTitle()
                // + "|";
                // Werkstueckeigenschaft prop = new Werkstueckeigenschaft();
                // prop.setTitel("TifHeaderImagedescription");
                // prop.setWert(description);
                // workProperties.add(prop);
                // }

                // Add collection names attached to the current record
                if (this.currentCollectionList != null) {
                    MetadataType mdTypeCollection = this.prefs.getMetadataTypeByName("singleDigCollection");
                    for (String collection : this.currentCollectionList) {
                        Metadata mdCollection = new Metadata(mdTypeCollection);
                        mdCollection.setValue(collection);
                        logicalDS.addMetadata(mdCollection);
                    }
                }

            } catch (PreferencesException e) {
                logger.error(e.getMessage(), e);
                throw new ImportPluginException("Cannot read digital document", e);
            } catch (MetadataTypeNotAllowedException e) {
                logger.error(e.getMessage(), e);
                if (logicalDS != null) {
                    throw new ImportPluginException("Metadata fo type " + "singleDigCollection" + " is not allowed for "
                            + logicalDS.getType().getName(), e);
                }

            }

        }
        return ff;
    }

    @Override
    public String getImportFolder() {
        return this.importFolder;
    }

    @Override
    public String getProcessTitle() {

        String answer = "";

        String suffix = "";
        if (StringUtils.isNotBlank(identifierAnalogVolume) && ConfigPlugins.getPluginConfig(this).getBoolean("useAnalogIdenifierInTitle", false)) {
            suffix = identifierAnalogVolume;
        } else if (StringUtils.isNotBlank(identifierAnalog) && ConfigPlugins.getPluginConfig(this).getBoolean("useAnalogIdenifierInTitle", false)) {
            suffix = identifierAnalog;
        } else {
            suffix = currentIdentifier;
        }

        if (suffix.contains(" ")) {
            suffix = suffix.substring(0, suffix.indexOf(" "));
        }

        if (StringUtils.isNotBlank(this.ats)) {
            answer = ats.toLowerCase() + "_" + suffix;
        } else {
            answer = suffix;
        }
        if (!ConfigPlugins.getPluginConfig(this).getBoolean("useAnalogIdenifierInTitle", false)) {
            if (StringUtils.isNotBlank(volumeNumber)) {
                answer = answer + "_" + volumeNumber;
            }
        }
        return answer;
    }

    @Override
    public List<ImportObject> generateFiles(List<Record> records) {
        List<ImportObject> answer = new ArrayList<ImportObject>();

        for (Record r : records) {
            form.addProcessToProgressBar();
            processProperties = new ArrayList<Processproperty>();
            workProperties = new ArrayList<Masterpieceproperty>();
            templateProperties = new ArrayList<Templateproperty>();
            this.data = r.getData();
            this.currentCollectionList = r.getCollections();
            this.imagefolder = r.getId();
            ImportObject io = new ImportObject();
            Fileformat ff = null;
            try {
                ff = convertData();
            } catch (ImportPluginException e1) {
                io.setErrorMessage(e1.getMessage());
            }
            io.setProcessTitle(getProcessTitle());
            if (ff != null) {
                r.setId(this.currentIdentifier);
                try {
                    MetsMods mm = new MetsMods(this.prefs);
                    mm.setDigitalDocument(ff.getDigitalDocument());
                    String fileName = getImportFolder() + getProcessTitle() + ".xml";
                    logger.debug("Writing '" + fileName + "' into given folder...");
                    mm.write(fileName);
                    io.setMetsFilename(fileName);

                    moveImages();
                    io.setImportReturnValue(ImportReturnValue.ExportFinished);

                    io.setProcessProperties(processProperties);
                    io.setTemplateProperties(templateProperties);
                    io.setWorkProperties(workProperties);
                } catch (PreferencesException e) {
                    logger.error(e.getMessage(), e);
                    io.setImportReturnValue(ImportReturnValue.InvalidData);
                } catch (WriteException e) {
                    logger.error(e.getMessage(), e);
                    io.setImportReturnValue(ImportReturnValue.WriteError);
                } catch (ImportPluginException e) {
                    logger.error(e.getMessage(), e);
                    io.setImportReturnValue(ImportReturnValue.WriteError);
                }
            } else {
                io.setImportReturnValue(ImportReturnValue.InvalidData);
            }
            answer.add(io);
        }

        return answer;
    }

    public void moveImages() throws ImportPluginException {
        // NEW
        String basedir = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/import/");
        Path path = Paths.get(basedir, imagefolder);

        logger.debug("looking for images folder in " + path.toString());
        if (path.toFile().exists() && path.toFile().isDirectory()) {
            Path destination = Paths.get(importFolder, getProcessTitle());

            if (!Files.exists(destination)) {
                try {
                    Files.createDirectory(destination);
                } catch (IOException e) {
                    logger.error(e);
                }
            }
            //            File destinationRoot = new File();
            //            if (!destinationRoot.exists()) {
            //                destinationRoot.mkdir();
            //            }
            try {

                DirectoryStream<Path> directoryStream = Files.newDirectoryStream(path);
                for (Path currentElement : directoryStream) {
                    if (Files.isDirectory(currentElement)) {
                        Path dest = Paths.get(destination.toString(), currentElement.getFileName().toString());
                        FileUtils.copyDirectory(currentElement.toFile(), dest.toFile());
                    } else {
                        Path dest = Paths.get(destination.toString(), "images", FOLDER_PREFIX + getProcessTitle() + FOLDER_SUFFIX);
                        if (!Files.exists(dest)) {
                            try {
                                Files.createDirectory(dest);
                            } catch (IOException e) {
                                logger.error(e);
                            }
                        }
                        Path p = Paths.get(dest.toString(), currentElement.getFileName().toString());

                        FileUtils.copyFile(currentElement.toFile(), p.toFile());


                    }
                }

                //                for (File file : folder.listFiles()) {
                //                    if (file.isDirectory()) {
                //                        FileUtils.copyDirectory(file, new File(destinationRoot, file.getName()));
                //                    } else {
                //
                //                        File destinationImages = new File(destinationRoot, "images");
                //                        if (!destinationImages.exists()) {
                //                            destinationImages.mkdir();
                //                        }
                //                        File destinationTif = new File(destinationImages, FOLDER_PREFIX + getProcessTitle() + FOLDER_SUFFIX);
                //                        if (!destinationTif.exists()) {
                //                            destinationTif.mkdir();
                //                        }
                //                        FileUtils.copyFile(file, new File(destinationTif, file.getName()));
                //                    }
                //                }
                // FileUtils.copyDirectory(folder, destinationTif);
            } catch (IOException e) {
                logger.error(this.currentIdentifier + ": " + e.getMessage(), e);
                throw new ImportPluginException(e);
            }
        }
    }

    @Override
    public void setImportFolder(String folder) {
        this.importFolder = folder;
    }

    @Override
    public List<Record> splitRecords(String records) {
        return new ArrayList<Record>();
    }

    @Override
    public List<Record> generateRecordsFromFile() {
        List<Record> records = new ArrayList<Record>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(importFile));
            String line = null;
            while ((line = br.readLine()) != null) {
                Record rec = new Record();
                rec.setData(line);
                records.add(rec);
            }
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return records;
    }

    @Override
    public List<Record> generateRecordsFromFilenames(List<String> filenames) {
        String folder = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/goobi/import/");
        List<Record> records = new ArrayList<Record>();
        for (String filename : filenames) {
            File f = new File(folder, filename);
            imagefolder = filename.replace(".xml", "");
            try {
                Document doc = new SAXBuilder().build(f);
                if (doc != null && doc.getRootElement() != null) {
                    Record record = new Record();
                    record.setData(f.getAbsolutePath());
                    record.setId(imagefolder);
                    records.add(record);
                } else {
                    logger.error("Could not parse '" + filename + "'.");
                }
            } catch (JDOMException e) {
                logger.error(e.getMessage(), e);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }

        }
        return records;
    }

    @Override
    public void setFile(File importFile) {
        this.importFile = importFile;
    }

    @Override
    public List<String> splitIds(String ids) {
        return new ArrayList<String>();
    }

    @Override
    public List<ImportType> getImportTypes() {
        List<ImportType> answer = new ArrayList<ImportType>();
        answer.add(ImportType.FOLDER);
        return answer;
    }

    @Override
    public List<ImportProperty> getProperties() {
        return new ArrayList<ImportProperty>();
    }

    @Override
    public List<String> getAllFilenames() {
        List<String> answer = new ArrayList<String>();
        String folder = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/goobi/import/");
        File f = new File(folder);
        if (f.exists() && f.isDirectory()) {
            String[] files = f.list(xml);
            for (String file : files) {
                answer.add(file);
            }
            Collections.sort(answer);
        }
        return answer;

    }

    @Override
    public void deleteFiles(List<String> selectedFilenames) {
        String folder = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/goobi/import/");
        for (String filename : selectedFilenames) {
            // removing mets file
            File f = new File(folder, filename);
            FileUtils.deleteQuietly(f);

            // removing anchor file
            File anchor = new File(folder, filename.replace(".xml", "_anchor.xml"));
            if (anchor.exists()) {
                FileUtils.deleteQuietly(anchor);
            }

            // renaming images folder
            File image = new File(folder, imagefolder);
            if (image.exists()) {
                try {
                    FileUtils.deleteDirectory(image);
                } catch (IOException e) {

                }
            }

        }
    }

    @Override
    public List<DocstructElement> getCurrentDocStructs() {
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

    // private static FilenameFilter images = new FilenameFilter() {
    // @Override
    // public boolean accept(File folder, String name) {
    // return name.endsWith("tif");
    // }
    // };

    private static FilenameFilter xml = new FilenameFilter() {
        @Override
        public boolean accept(File folder, String name) {
            return name.endsWith(".xml") && !name.contains("_anchor");
        }
    };

    private String createAtstsl(String myTitle, String autor) {
        if (ConfigPlugins.getPluginConfig(this).getBoolean("useSpecialAts", false)) {
            return createSpecialATS(myTitle);
        }

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
        new UghHelper();
        new UghHelper();
        /* im ATS-TSL die Umlaute ersetzen */
        //        if (FacesContext.getCurrentInstance() != null) {
        myAtsTsl = UghHelper.convertUmlaut(myAtsTsl);
        //        }
        myAtsTsl = myAtsTsl.replaceAll("[\\W]", "");
        return myAtsTsl;
    }

    private String createSpecialATS(String myTitle) {
        String titleValue = "";
        if (myTitle != null && !myTitle.isEmpty()) {
            if (myTitle.contains(" ")) {
                titleValue = myTitle.substring(0, myTitle.indexOf(" "));
            } else {
                titleValue = myTitle;
            }
        }
        String myAtsTsl = "";

        if (titleValue.length() > 6) {
            myAtsTsl = titleValue.substring(0, 6);
        } else {
            myAtsTsl = titleValue;
        }
        new UghHelper();
        new UghHelper();
        myAtsTsl = UghHelper.convertUmlaut(myAtsTsl);
        myAtsTsl = myAtsTsl.replaceAll("[\\W]", "");
        return myAtsTsl.toLowerCase();
    }

    @Override
    public void setForm(MassImportForm form) {
this.form = form;
    }

}
