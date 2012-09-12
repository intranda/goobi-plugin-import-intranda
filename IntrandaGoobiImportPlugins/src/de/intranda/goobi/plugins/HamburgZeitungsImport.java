//package de.intranda.goobi.plugins;
//
//import java.io.File;
//import java.io.FilenameFilter;
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//
//import net.xeoh.plugins.base.annotations.PluginImplementation;
//
//import org.apache.commons.io.FileUtils;
//import org.goobi.production.Import.Record;
//import org.goobi.production.enums.ImportType;
//import org.goobi.production.enums.PluginType;
//import org.goobi.production.plugin.interfaces.IImportPlugin;
//import org.goobi.production.plugin.interfaces.IPlugin;
//import org.jdom.Document;
//import org.jdom.JDOMException;
//import org.jdom.input.SAXBuilder;
//
//import de.sub.goobi.config.ConfigPlugins;
//import de.sub.goobi.helper.exceptions.ImportPluginException;
//
//@PluginImplementation
//public class HamburgZeitungsImport extends IntrandaGoobiImport implements IImportPlugin, IPlugin {
//
//	public HamburgZeitungsImport() {
//		super();
//	}
//
//	private static final String NAME = "HamburgerZeitungsImport";
//
//	
//	
//	public String getId() {
//		return NAME;
//	}
//
//	@Override
//	public PluginType getType() {
//		return PluginType.Import;
//	}
//
//	@Override
//	public String getTitle() {
//		return NAME;
//	}
//
//	@Override
//	public List<ImportType> getImportTypes() {
//		List<ImportType> answer = new ArrayList<ImportType>();
//		answer.add(ImportType.FOLDER);
//		return answer;
//	}
//
//	@Override
//	public List<String> getAllFilenames() {
//		List<String> answer = new ArrayList<String>();
//		String folder = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/goobi/import/");
//		File f = new File(folder);
//		if (f.exists() && f.isDirectory()) {
//			String[] files = f.list(FOLDER);
//			for (String file : files) {
//				answer.add(file);
//			}
//			Collections.sort(answer);
//		}
//		return answer;
//	}
//
//	@Override
//	public List<Record> generateRecordsFromFilenames(List<String> filenames) {
//		String basefolder = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/goobi/import/");
//		List<Record> records = new ArrayList<Record>();
//		for (String filename : filenames) {
//			File folder = new File(basefolder, filename);
//			File meta = new File(folder, "meta.xml");
//
//			File images = new File(folder, "images");
//			File tif = new File(images, "Newspaper_tif");
//			imagefolder = tif.getAbsolutePath();
//			try {
//				Document doc = new SAXBuilder().build(meta);
//				if (doc != null && doc.getRootElement() != null) {
//					Record record = new Record();
//					record.setData(meta.getAbsolutePath());
//					record.setId(imagefolder);
//					records.add(record);
//				} else {
//					logger.error("Could not parse '" + meta.getAbsolutePath() + "'.");
//				}
//			} catch (JDOMException e) {
//				logger.error(e.getMessage(), e);
//			} catch (IOException e) {
//				logger.error(e.getMessage(), e);
//			}
//		}
//		return records;
//	}
//
//	@Override
//	public void deleteFiles(List<String> selectedFilenames) {
//		String folder = ConfigPlugins.getPluginConfig(this).getString("basedir", "/opt/digiverso/goobi/import/");
//		for (String filename : selectedFilenames) {
//			// removing mets file
//			File f = new File(folder, filename);
//			try {
//				FileUtils.deleteDirectory(f);
//			} catch (IOException e) {
//				logger.warn(e);
//			}
//		}
//	}
//
//	@Override
//	public void moveImages() throws ImportPluginException {
//		File folder = new File(imagefolder);
//		if (folder.exists() && folder.isDirectory()) {
//			File destinationRoot = new File(importFolder, getProcessTitle());
//			if (!destinationRoot.exists()) {
//				destinationRoot.mkdir();
//			}
//			File destinationImages = new File(destinationRoot, "images");
//			if (!destinationImages.exists()) {
//				destinationImages.mkdir();
//			}
//			File destinationTif = new File(destinationImages, getProcessTitle()+ "_tif");
////			if (!destinationTif.exists()) {
////				destinationTif.mkdir();
////			}
//			try {
////				for (File file : folder.listFiles()) {
//					FileUtils.copyDirectory(folder, destinationTif);
////				}
//				// FileUtils.copyDirectory(folder, destinationTif);
//			} catch (IOException e) {
//				logger.error(this.currentIdentifier + ": " + e.getMessage(), e);
//				throw new ImportPluginException(e);
//			}
//		}
//	}
//
//	private static FilenameFilter FOLDER = new FilenameFilter() {
//		@Override
//		public boolean accept(File folder, String name) {
//			File f = new File(folder, name);
//			return f.isDirectory();
//			// return name.endsWith(".xml") && !name.contains("_anchor");
//		}
//	};
//}
