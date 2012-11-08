package de.intranda.goobi.plugins;

import net.xeoh.plugins.base.annotations.PluginImplementation;

import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IValidatorPlugin;

import de.sub.goobi.Beans.Prozess;
import de.sub.goobi.Beans.Schritt;
import de.sub.goobi.helper.Helper;

	@PluginImplementation
	public class SampleCommand implements IValidatorPlugin, IPlugin {

		private Schritt step;
		
		@Override
		public PluginType getType() {
			
			return PluginType.Validation;
		}

		@Override
		public String getTitle() {
			return "SampleCommand";
		}

		@Override
		public String getDescription() {
			return "SampleCommand";
		}

		@Override
		public void initialize(Prozess inProcess) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public boolean validate() {
			
			Helper.setFehlerMeldung("test123");
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public Schritt getStep() {
			return step;
		}

		@Override
		public void setStep(Schritt step) {
			this.step = step;
			
		}
		
	
}
