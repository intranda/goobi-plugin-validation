package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import net.xeoh.plugins.base.annotations.PluginImplementation;

import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.cli.helper.WikiFieldHelper;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IValidatorPlugin;
import org.xml.sax.SAXException;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;

@PluginImplementation
public class AltoValidationPlugin implements IValidatorPlugin, IPlugin {

    private static final Logger logger = Logger.getLogger(AltoValidationPlugin.class);

    private static final String PLUGIN_NAME = "AltoValidation";
    private static File xsdFile = null;
    private static SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    private static Schema schema;
    private static Validator validator;

    private File jp2Folder = null;
    private File altoFolder = null;
    private File validationFolder = null;

    private Step step;

    @Override
    public PluginType getType() {
        return PluginType.Validation;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    @Override
    public String getDescription() {
        return PLUGIN_NAME;
    }

    @Override
    public void initialize(Process inProcess) {

    }

    @Override
    public boolean validate() {
        try {
            jp2Folder = new File(step.getProzess().getImagesOrigDirectory(true));
        } catch (SwapException e) {
            logger.error(e);
        } catch (DAOException e) {
            logger.error(e);
        } catch (IOException e) {
            logger.error(e);
        } catch (InterruptedException e) {
            logger.error(e);
        }
        try {
            altoFolder = new File(step.getProzess().getAltoDirectory());
        } catch (SwapException e) {
            logger.error(e);
        } catch (DAOException e) {
            logger.error(e);
        } catch (IOException e) {
            logger.error(e);
        } catch (InterruptedException e) {
            logger.error(e);
        }

        try {
            validationFolder = new File(step.getProzess().getProcessDataDirectory() + "validation/alto/");
            if (!validationFolder.exists()) {
                validationFolder.mkdirs();
            }
        } catch (SwapException e) {
            logger.error(e);
        } catch (DAOException e) {
            logger.error(e);
        } catch (IOException e) {
            logger.error(e);
        } catch (InterruptedException e) {
            logger.error(e);
        }

        xsdFile = new File(ConfigurationHelper.getInstance().getXsltFolder(), "alto-v2.0.xsd");
        List<String> jp2Files = Arrays.asList(jp2Folder.list(jp2FilenameFilter));
        List<File> altoFiles = Arrays.asList(altoFolder.listFiles(xmlFilenameFilter));

        if (jp2Files.size() != altoFiles.size()) {
            String message = "Numbers of jp2-files and alto-files do not match!";
            Helper.setFehlerMeldung(message);
            ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error", message), step.getProzess().getId());
            return false;
        }
        Collections.sort(altoFiles);
        Collections.sort(jp2Files);
        int i = 0;
        for (String str : jp2Files) {
            String altoBasename = getBasename(altoFiles.get(i).getName());
            String jp2Basename = getBasename(str);
            if (!jp2Basename.equals(altoBasename)) {
                String message = "Names do not match! (" + altoBasename + " and " + jp2Basename + ")";
                Helper.setFehlerMeldung(message);
                ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error", message), step.getProzess()
                        .getId());
                return false;
            }
            i++;
        }

        try {
            schema = factory.newSchema(new StreamSource(xsdFile));
        } catch (SAXException e1) {
            String message = "Can not parse " + xsdFile;
            Helper.setFehlerMeldung(message);
            ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error", message), step.getProzess().getId());
            return false;
        }

        validator = schema.newValidator();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY);

        File validationFile = new File(validationFolder, "altoValidation_" + format.format(new Date()) + ".txt");
        FileWriter writer = null;
        try {
            writer = new FileWriter(validationFile);
        } catch (IOException e1) {

            String message = "Can not open " + validationFile + " for writing";
            Helper.setFehlerMeldung(message);
            ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error", message), step.getProzess().getId());
            return false;
        }

        boolean allValid = true;
        for (File xml : altoFiles) {
            try {
                if (!validateAgainstXsd(xml)) {
                    writer.write(xml + " is not valid.\n");
                    allValid = false;
                } else {
                    writer.write(xml + " is valid.\n");
                }
            } catch (SAXException e) {
                String message = "Could not parse " + xsdFile.getAbsolutePath();
                Helper.setFehlerMeldung(message);
                ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error", message), step.getProzess()
                        .getId());
                allValid = false;
            } catch (IOException e) {
                String message = "Could not read " + xml;
                Helper.setFehlerMeldung(message);
                ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error", message), step.getProzess()
                        .getId());
                allValid = false;
            }
        }
        try {
            writer.close();
        } catch (IOException e) {
            String message = "Can not close " + validationFile + " from writing";
            Helper.setFehlerMeldung(message);
            ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error", message), step.getProzess().getId());
            return false;
        }
        if (!allValid) {
            return false;
        }
        return true;
    }

    static boolean validateAgainstXsd(File xmlFile) throws SAXException, IOException {
        try {
            validator.validate(new StreamSource(xmlFile));
        } catch (SAXException e) {
            return false;
        }
        return true;
    }

    private static String getBasename(String str) {
        int startIndex = str.lastIndexOf(File.pathSeparatorChar);
        startIndex = startIndex < 0 ? 0 : startIndex;
        return str.substring(startIndex, str.lastIndexOf('.'));
    }

    public static final FilenameFilter jp2FilenameFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            name = name.toLowerCase();
            if (name.endsWith(".jp2") || name.endsWith(".JP2")) {
                return true;
            }
            return false;
        }
    };

    public static final FilenameFilter xmlFilenameFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            name = name.toLowerCase();
            if (name.endsWith(".xml")) {
                return true;
            }
            return false;
        }
    };

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
