package de.intranda.goobi.plugins;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import net.xeoh.plugins.base.annotations.PluginImplementation;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.goobi.production.cli.helper.WikiFieldHelper;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IValidatorPlugin;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import de.sub.goobi.Beans.Prozess;
import de.sub.goobi.Beans.Schritt;
import de.sub.goobi.Persistence.ProzessDAO;
import de.sub.goobi.Persistence.apache.FolderInformation;
import de.sub.goobi.Persistence.apache.ProcessManager;
import de.sub.goobi.Persistence.apache.ProcessObject;
import de.sub.goobi.Persistence.apache.StepObject;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;

@PluginImplementation
public class JP2ValidationCommand implements IValidatorPlugin, IPlugin {
	private static final Logger logger = Logger.getLogger(JP2ValidationCommand.class);

	private String name = "intrandaJpylyzerValidation";

	private Schritt step = null;

	private StepObject stepObject = null;

	private static FilenameFilter jp2Filter = new FilenameFilter() {

		@Override
		public boolean accept(File dir, String name) {

			return (name.endsWith("jp2") || name.endsWith("JP2"));
		}
	};

	@Override
	public PluginType getType() {
		return PluginType.Validation;
	}

	@Override
	public String getTitle() {
		return name;
	}

	@Override
	public String getDescription() {
		return name;
	}

	@Override
	public Schritt getStep() {
		return step;
	}

	@Override
	public void setStep(Schritt step) {
		this.step = step;
	}

	@Override
	public void initialize(Prozess inProcess) {
	}

	@Override
	public boolean validate() {
		boolean returnvalue = true;
		File folder = null;
		String foldername;
		try {
			if (step != null) {
				foldername = step.getProzess().getImagesTifDirectory(false);
				folder = new File(foldername);
			} else {
				ProcessObject po = ProcessManager.getProcessObjectForId(stepObject.getProcessId());
				FolderInformation fi = new FolderInformation(stepObject.getProcessId(), po.getTitle());
				foldername = fi.getImagesTifDirectory(false);
				folder = new File(foldername);
			}
		} catch (SwapException e) {
			logger.error(e);
			Helper.setFehlerMeldung("Error " + e.getMessage());
			return false;
		} catch (DAOException e) {
			logger.error(e);
			Helper.setFehlerMeldung("Error " + e.getMessage());
			return false;
		} catch (IOException e) {
			logger.error(e);
			Helper.setFehlerMeldung("Error " + e.getMessage());
			return false;
		} catch (InterruptedException e) {
			logger.error(e);
			Helper.setFehlerMeldung("Error " + e.getMessage());
			return false;
		}

		if (!folder.exists() || !folder.isDirectory()) {
			Helper.setFehlerMeldung("Folder " + folder.getName() + " does not exist");
			return false;
		}
		String[] jp2files = folder.list(jp2Filter);
		if (jp2files == null || jp2files.length == 0) {
			Helper.setFehlerMeldung("Found no jp2 files.");
			return false;
		}

		Map<String, String> files = new HashMap<String, String>();
		for (String jp2file : jp2files) {
			try {

				String command = "/opt/digiverso/goobi/scripts/jpylyzer/jpylyzer.py " + foldername + jp2file;

				String validationMessage = callShell(command);

				String xmlFile = foldername + jp2file.replace("jp2", "xml").replace("JP2", "xml");
				FileWriter fw = new FileWriter(xmlFile);
				BufferedWriter out = new BufferedWriter(fw);
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
								fail = (Element) fail.getChildren().get(0);
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
				FileUtils.deleteQuietly(new File(xmlFile));
			} catch (JDOMException e) {
				Helper.setFehlerMeldung("Cannot read jpylyzer output, it is not a valid xml file.");
				logger.error(e);
				return false;

			} catch (IOException e) {
				Helper.setFehlerMeldung("Cannot read jpylyzer output.", e);
				logger.error(e);
				return false;
			} catch (InterruptedException e) {
				Helper.setFehlerMeldung("Cannot call jpylyzer.", e);
				logger.error(e);
				return false;
			}

		}
		for (String key : files.keySet()) {
			if (!files.get(key).equals("")) {
				Helper.setFehlerMeldung("Error in " + key + ": " + files.get(key));
				logger.info("Error in " + key + ": " + files.get(key));
				if (step != null) {
					step.getProzess().setWikifield(
							WikiFieldHelper.getWikiMessage(step.getProzess().getWikifield(), "error", "Error in " + key + ": " + files.get(key)));
				} else {
					ProcessObject po = ProcessManager.getProcessObjectForId(stepObject.getProcessId());
					ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(po.getWikifield(), "error", "Error in " + key + ": " + files.get(key)), stepObject.getProcessId());
				}
				returnvalue = false;
			}
		}
		if (!returnvalue && step != null) {
			// saving step so wikifield gets saved
			try {
				new ProzessDAO().save(step.getProzess());
			} catch (DAOException e) {
				logger.error(e);
			}

		}

		return returnvalue;
	}

	public static String callShell(String command) throws IOException, InterruptedException {
		InputStream is = null;
		InputStream es = null;
		OutputStream out = null;
		StringBuilder sb = new StringBuilder();
		try {

			Process process = Runtime.getRuntime().exec(command);
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

	public StepObject getStepObject() {
		return stepObject;
	}

	public void setStepObject(StepObject so) {
		this.stepObject = so;
	}

}
