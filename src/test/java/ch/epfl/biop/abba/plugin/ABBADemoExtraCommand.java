package ch.epfl.biop.abba.plugin;

import ch.epfl.biop.atlas.aligner.MultiSlicePositioner;
import ch.epfl.biop.atlas.aligner.plugin.ABBACommand;
import ij.IJ;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = ABBACommand.class,
        menuPath = "Plugins>BIOP>Atlas>Multi Image To Atlas>Plugin>[List slices - Demo ABBA plugin]",
        description = "Plugin example for ABBA.")
public class ABBADemoExtraCommand implements ABBACommand {

    @Parameter
    MultiSlicePositioner mp; //COMPULSORY : name = mp

    @Override
    public void run() {
        IJ.log("Current ABBA slices: "+mp.getSlices());
    }

}
