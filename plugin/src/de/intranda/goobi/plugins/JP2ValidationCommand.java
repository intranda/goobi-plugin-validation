package de.intranda.goobi.plugins;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

import org.apache.log4j.Logger;
import org.goobi.beans.LogEntry;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IValidatorPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
public class JP2ValidationCommand implements IValidatorPlugin, IPlugin {
    private static final Logger logger = Logger.getLogger(JP2ValidationCommand.class);

    private String name = "intrandaJpylyzerValidation";

    private Step step = null;

    private String resultPath = null;

    private static boolean saveResult = true;

    public static final DirectoryStream.Filter<Path> jp2FileFilter = new DirectoryStream.Filter<Path>() {

        @Override
        public boolean accept(Path dir) throws IOException {
            dir = Paths.get(dir.toString().toLowerCase());
            if (dir.endsWith(".jp2") || dir.endsWith(".JP2")) {
                return true;
            }
            return false;
        }
    };

    //	private static FilenameFilter jp2Filter = new FilenameFilter() {
    //
    //		@Override
    //		public boolean accept(File dir, String name) {
    //
    //			return (name.endsWith("jp2") || name.endsWith("JP2"));
    //		}
    //	};

    /*
     * (non-Javadoc)
     * 
     * @see org.goobi.production.plugin.interfaces.IPlugin#getType()
     */
    @Override
    public PluginType getType() {
        return PluginType.Validation;
    }

    @Override
    public String getTitle() {
        return name;
    }

    public String getDescription() {
        return name;
    }

    @Override
    public Step getStep() {
        return null;
    }

    @Override
    public void setStep(Step step) {

        this.step = step;
    }

    @Override
    public void initialize(Process inProcess) {
    }

    @Override
    public boolean validate() {
        boolean returnvalue = true;
        Path folder = null;
        String foldername;
        String returnMessage = "";

        try {
            if (ConfigurationHelper.getInstance().useS3()) {
                String workingStorage = System.getenv("WORKING_STORAGE");
                Path workDir = Paths.get(workingStorage, UUID.randomUUID().toString());
                StorageProvider.getInstance()
                .downloadDirectory(Paths.get(step.getProzess().getImagesTifDirectory(false)), workDir);
                foldername = workDir.toAbsolutePath().toString();
                folder = workDir;
            } else {
                foldername = step.getProzess().getImagesTifDirectory(false);
                folder = Paths.get(foldername);
            }
        } catch (SwapException e1) {
            logger.error(e1);
            return false;
        } catch (DAOException e1) {
            logger.error(e1);
            return false;
        } catch (IOException e1) {
            logger.error(e1);
            return false;
        } catch (InterruptedException e1) {
            logger.error(e1);
            return false;
        }
        if (!StorageProvider.getInstance().isDirectory(folder)) {
            Helper.setFehlerMeldung("Folder " + folder.getFileName() + " does not exist");
            returnMessage = "Folder " + folder.getFileName() + " does not exist";
            updateGoobi(returnMessage);
            return false;
        }
        List<String> jp2files = StorageProvider.getInstance().list(folder.toString(), jp2FileFilter);
        if (jp2files == null || jp2files.size() == 0) {
            Helper.setFehlerMeldung("Found no jp2 files.");
            returnMessage = "Found no jp2 files.";
            updateGoobi(returnMessage);

            return false;
        }
        if (saveResult) {
            String datetime = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            resultPath = foldername + "../../validation/";
            Path resultFile = Paths.get(resultPath);
            try {
                if (!StorageProvider.getInstance().isDirectory(resultFile)
                        && !StorageProvider.getInstance().isDirectory(Files.createDirectory(resultFile))) {
                    Helper.setFehlerMeldung("Cannot create output directory.");
                    logger.error("Cannot create output directory.");
                    updateGoobi("Cannot create output directory.");
                    return false;
                }
            } catch (IOException e) {
                Helper.setFehlerMeldung("Cannot create output directory.");
                logger.error("Cannot create output directory.");
                updateGoobi("Cannot create output directory.");
                //				e.printStackTrace();
                return false;
            }

            resultPath = resultPath + datetime + "_jpylyzer";
        }
        Map<String, String> files = new HashMap<>();
        for (String jp2file : jp2files) {
            try {

                String command = "/opt/digiverso/goobi/scripts/jpylyzer/jpylyzer.py " + foldername + jp2file;

                String validationMessage = callShell(command);

                String xmlFile = foldername + jp2file.replace("jp2", "xml").replace("JP2", "xml");
                BufferedWriter out = Files.newBufferedWriter(Paths.get(xmlFile));
                out.write(validationMessage);
                out.close();

                SAXBuilder builder = new SAXBuilder(false);
                builder.setValidation(false);
                builder.setFeature("http://xml.org/sax/features/validation", false);
                builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
                builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

                Document doc = builder.build(xmlFile);
                Element root = doc.getRootElement();

                Element validation = root.getChild("isValidJP2");
                if (validation.getValue().equalsIgnoreCase("True")) {
                    files.put(jp2file, "");
                    // if (verbose) {
                    // System.out.println("File " + jp2file + " is valid.");
                    // }
                } else {
                    // System.err.println("File " + jp2file + " is not valid.");
                    Element tests = root.getChild("tests");
                    @SuppressWarnings("unchecked")
                    List<Element> failedTests = tests.getChildren();
                    for (Element test : failedTests) {
                        String errorMessage = "File failed test " + test.getName() + "<br/>";

                        @SuppressWarnings("unchecked")
                        List<Element> fails = test.getChildren();
                        for (Element fail : fails) {
                            while (fail.getChildren() != null && fail.getChildren().size() > 0) {
                                fail = fail.getChildren().get(0);
                            }
                            if (fail.getValue() != null && fail.getValue().length() > 0) {
                                errorMessage += " " + fail.getName() + ": " + fail.getValue() + "<br/>";
                            } else {
                                errorMessage += " " + fail.getName() + "<br/>";
                            }
                        }
                        // System.err.println(errorMessage);
                        files.put(jp2file, errorMessage);
                    }

                }
                // FileUtils.deleteQuietly(new File(xmlFile));
                if (!saveResult) {
                    StorageProvider.getInstance().deleteFile(Paths.get(xmlFile));
                } else {
                    Path source = Paths.get(xmlFile);

                    Path dest = Paths.get(resultPath);
                    if (!StorageProvider.getInstance().isDirectory(dest)
                            && !StorageProvider.getInstance().isDirectory(Files.createDirectory(dest))) {
                        Helper.setFehlerMeldung("Cannot create output directory.");
                        logger.error("Cannot create output directory.");
                        return false;
                    }
                    StorageProvider.getInstance().copyFile(source, dest);
                    StorageProvider.getInstance().deleteFile(source);
                }

            } catch (JDOMException e) {
                Helper.setFehlerMeldung("Cannot read jpylyzer output, it is not a valid xml file.");
                logger.error(e);
                updateGoobi("Cannot read jpylyzer output, it is not a valid xml file.");
                return false;

            } catch (IOException e) {
                Helper.setFehlerMeldung("Cannot read jpylyzer output.", e);
                updateGoobi("Cannot read jpylyzer output.");
                logger.error(e);
                return false;
            } catch (InterruptedException e) {
                Helper.setFehlerMeldung("Cannot call jpylyzer.", e);
                updateGoobi("Cannot call jpylyzer.");
                logger.error(e);
                return false;
            }

        }
        for (String key : files.keySet()) {
            if (!files.get(key).equals("")) {
                Helper.setFehlerMeldung("Error in " + key + ": " + files.get(key));
                logger.info("Error in " + key + ": " + files.get(key));
                // if (step != null) {
                // step.getProzess().setWikifield(
                // WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error",
                // "Error in " + key + ": " + files.get(key)));
                // } else {
                // ProcessObject po =
                // ProcessManager.getProcessObjectForId(stepObject.getProcessId());

                LogEntry logEntry = new LogEntry();
                logEntry.setContent("Error in " + key + ": " + files.get(key));
                logEntry.setCreationDate(new Date());
                logEntry.setProcessId(step.getProzess().getId());
                logEntry.setType(LogType.ERROR);

                logEntry.setUserName("automatic");

                ProcessManager.saveLogEntry(logEntry);

                // }
                returnvalue = false;
            }
        }
        if (ConfigurationHelper.getInstance().useS3()) {
            StorageProvider.getInstance().deleteDir(folder);
        }
        return returnvalue;
    }

    private void updateGoobi(String message) {

        LogEntry logEntry = new LogEntry();
        logEntry.setContent(message);
        logEntry.setCreationDate(new Date());
        logEntry.setProcessId(step.getProzess().getId());
        logEntry.setType(LogType.ERROR);

        logEntry.setUserName("automatic");
        ProcessManager.saveLogEntry(logEntry);

        // }
    }

    public static String callShell(String command) throws IOException, InterruptedException {
        InputStream is = null;
        InputStream es = null;
        OutputStream out = null;
        StringBuilder sb = new StringBuilder();
        try {

            java.lang.Process process = Runtime.getRuntime().exec(command);
            is = process.getInputStream();
            es = process.getErrorStream();
            out = process.getOutputStream();
            Scanner scanner = new Scanner(is);
            while (scanner.hasNextLine()) {
                String myLine = scanner.nextLine();
                sb.append(myLine);
            }

            scanner.close();
            scanner = new Scanner(es);
            while (scanner.hasNextLine()) {
                sb.append(scanner.nextLine());
            }
            scanner.close();
            return sb.toString();

        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    is = null;
                }
            }
            if (es != null) {
                try {
                    es.close();
                } catch (IOException e) {
                    es = null;
                }

            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    out = null;
                }
            }
        }
    }

    public static void main(String[] args) {
        System.out.println(new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()));
    }

    @Override
    public Step getStepObject() {
        return null;
    }

    @Override
    public void setStepObject(Step so) {
    }
}
