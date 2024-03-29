package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IValidatorPlugin;
import org.xml.sax.SAXException;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
public class AltoValidationPlugin implements IValidatorPlugin, IPlugin {

    private static final Logger logger = Logger.getLogger(AltoValidationPlugin.class);

    private static final String PLUGIN_NAME = "AltoValidation";
    private static Path xsdFile = null;
    private static SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    private static Schema schema;
    private static Validator validator;

    private Path jp2Folder = null;
    private Path altoFolder = null;
    private Path validationFolder = null;

    private Step step;

    @Override
    public PluginType getType() {
        return PluginType.Validation;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    public String getDescription() {
        return PLUGIN_NAME;
    }

    @Override
    public void initialize(Process inProcess) {

    }

    @Override
    public boolean validate() {
        try {
            jp2Folder = Paths.get(step.getProzess().getImagesOrigDirectory(true));
        } catch (SwapException | DAOException | IOException e) {
            logger.error(e);
        }
        try {
            altoFolder = Paths.get(step.getProzess().getOcrAltoDirectory());
        } catch (SwapException | IOException e) {
            logger.error(e);
        }

        try {
            validationFolder = Paths.get(step.getProzess().getProcessDataDirectory() + "validation/alto/");

            if (!StorageProvider.getInstance().isDirectory(validationFolder)) {
                StorageProvider.getInstance().createDirectories(validationFolder);
            }
        } catch (SwapException | IOException e) {
            logger.error(e);
        }

        xsdFile = Paths.get(ConfigurationHelper.getInstance().getXsltFolder(), "alto-v2.0.xsd");
        List<Path> jp2Files = StorageProvider.getInstance().listFiles(jp2Folder.toString(), jp2FileFilter);
        List<Path> altoFiles = StorageProvider.getInstance().listFiles(altoFolder.toString(), xmlFileFilter);
        //		List<String> jp2Files = Arrays.asList(jp2Folder.list(jp2FilenameFilter));
        //		List<Path> altoFiles = StorageProvider.getInstance().listFiles(altoFolder.listFiles(xmlFilenameFilter));
        if (!Files.exists(altoFolder)) {
            return true;
        }
        if (jp2Files.size() != altoFiles.size()) {
            String message = "Numbers of jp2-files and alto-files do not match!";
            writeErrorToLog(message);
            return false;
        }
        Collections.sort(altoFiles);
        Collections.sort(jp2Files);
        int i = 0;
        for (Path str : jp2Files) {
            String altoBasename = getBasename(altoFiles.get(i).getFileName().toString());
            String jp2Basename = getBasename(str.toString());
            if (!jp2Basename.equals(altoBasename)) {
                String message = "Names do not match! (" + altoBasename + " and " + jp2Basename + ")";
                writeErrorToLog(message);
                return false;
            }
            i++;
        }

        try (InputStream is = StorageProvider.getInstance().newInputStream(xsdFile)) {
            schema = factory.newSchema(new StreamSource(is));
        } catch (SAXException e1) {
            String message = "Can not parse " + xsdFile;
            logger.error(e1);
            writeErrorToLog(message);
            return false;
        } catch (IOException e1) {

            String message = "Can not open " + xsdFile;
            writeErrorToLog(message);
            return false;
        }

        validator = schema.newValidator();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY);

        //		File validationFile = new File(validationFolder, "altoValidation_" + format.format(new Date()) + ".txt");
        Path validationFile = validationFolder.resolve("altoValidation_" + format.format(new Date()) + ".txt");

        boolean allValid = true;
        try (OutputStream os = StorageProvider.getInstance().newOutputStream(validationFile);
                OutputStreamWriter writer = new OutputStreamWriter(os, "utf-8")) {
            for (Path xml : altoFiles) {
                try {
                    if (!validateAgainstXsd(xml)) {
                        writer.write(xml + " is not valid.\n");
                        allValid = false;
                    } else {
                        writer.write(xml + " is valid.\n");
                    }
                } catch (SAXException e) {
                    String message = "Could not parse " + xsdFile.toAbsolutePath();
                    writeErrorToLog(message);
                    allValid = false;
                } catch (IOException e) {
                    String message = "Could not read " + xml;
                    writeErrorToLog(message);
                    allValid = false;
                }
            }
        } catch (IOException e1) {

            String message = "Can not open " + validationFile + " for writing";
            writeErrorToLog(message);
            return false;
        }

        if (!allValid) {
            Helper.setFehlerMeldung("Some alto files are not valid.");
            return false;
        }
        return true;
    }

    private void writeErrorToLog(String message) {
        Helper.setFehlerMeldung(message);
        Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR, message, "automatic");
    }

    static boolean validateAgainstXsd(Path xmlFile) throws SAXException, IOException {
        try (InputStream is = StorageProvider.getInstance().newInputStream(xmlFile)) {
            validator.validate(new StreamSource(is));
        } catch (SAXException e) {
            return false;
        }
        return true;
    }

    private static String getBasename(String str) {
        int startIndex = str.lastIndexOf(FileSystems.getDefault().getSeparator());
        startIndex = startIndex < 0 ? 0 : startIndex;
        return str.substring(startIndex, str.lastIndexOf('.'));
    }

    public static final DirectoryStream.Filter<Path> jp2FileFilter = new DirectoryStream.Filter<Path>() {

        @Override
        public boolean accept(Path dir) throws IOException {
            dir = Paths.get(dir.toString().toLowerCase());
            return (dir.endsWith(".jp2") || dir.endsWith(".JP2"));
        }
    };
    public static final DirectoryStream.Filter<Path> xmlFileFilter = new DirectoryStream.Filter<Path>() {

        @Override
        public boolean accept(Path dir) throws IOException {
            dir = Paths.get(dir.toString().toLowerCase());
            return (dir.endsWith(".xml"));
        }
    };

    //	public static final FilenameFilter jp2FilenameFilter = new FilenameFilter() {
    //		@Override
    //		public boolean accept(File dir, String name) {
    //			name = name.toLowerCase();
    //			if (name.endsWith(".jp2") || name.endsWith(".JP2")) {
    //				return true;
    //			}
    //			return false;
    //		}
    //	};

    //	public static final FilenameFilter xmlFilenameFilter = new FilenameFilter() {
    //		@Override
    //		public boolean accept(File dir, String name) {
    //			name = name.toLowerCase();
    //			if (name.endsWith(".xml")) {
    //				return true;
    //			}
    //			return false;
    //		}
    //	};

    @Override
    public Step getStep() {
        return step;
    }

    @Override
    public void setStep(Step step) {
        this.step = step;
    }

    @Override
    public Step getStepObject() {
        return step;
    }

    @Override
    public void setStepObject(Step so) {
        this.step = so;
    }

}
