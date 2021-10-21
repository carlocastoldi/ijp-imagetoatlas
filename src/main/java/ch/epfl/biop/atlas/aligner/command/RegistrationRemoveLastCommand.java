package ch.epfl.biop.atlas.aligner.command;

import ch.epfl.biop.atlas.aligner.MultiSlicePositioner;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Plugin(type = Command.class,
        menuPath = "Plugins>BIOP>Atlas>Multi Image To Atlas>Align>ABBA - Remove Last Registration",
        description = "Remove the last registration of the current selected slices, if possible.")
public class RegistrationRemoveLastCommand implements Command {

    protected static Logger logger = LoggerFactory.getLogger(RegistrationRemoveLastCommand.class);

    @Parameter
    MultiSlicePositioner mp;

    @Override
    public void run() {
        logger.info("Remove last registration command called.");

        if (mp.getNumberOfSelectedSources()==0) {
            mp.log.accept("No slice selected");
            mp.warningMessageForUser.accept("No selected slice", "Please select the slice(s) you want to register");
            return;
        }

        mp.removeLastRegistration();
    }
}
