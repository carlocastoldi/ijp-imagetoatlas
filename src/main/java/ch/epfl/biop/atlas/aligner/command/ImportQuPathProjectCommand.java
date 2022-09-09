package ch.epfl.biop.atlas.aligner.command;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.atlas.aligner.MultiSlicePositioner;
import ch.epfl.biop.bdv.img.legacy.qupath.entity.QuPathEntryEntity;
import ch.epfl.biop.scijava.command.spimdata.QuPathProjectToBDVDatasetCommand;
import mpicbg.spim.data.generic.AbstractSpimData;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.bdvpg.scijava.services.SourceAndConverterService;

import java.io.File;
import java.util.concurrent.ExecutionException;

@Plugin(type = Command.class,
        menuPath = "Plugins>BIOP>Atlas>Multi Image To Atlas>Import>ABBA - Import QuPath Project",
        description = "Import images of a QuPath project as slices into ABBA")
public class ImportQuPathProjectCommand implements Command {

    @Parameter
    MultiSlicePositioner mp;

    @Parameter(label = "QuPath project file (.qpproj)")
    File qupath_project;

    @Parameter(label = "Initial axis position (0 = front, mm units)", style="format:0.000", stepSize = "0.1")
    double slice_axis_initial;

    @Parameter(label = "Axis increment between slices (mm, can be negative for reverse order)", style="format:0.000", stepSize = "0.01")
    double increment_between_slices;

    @Parameter
    CommandService command_service;

    @Parameter
    SourceAndConverterService sac_service;

    @Override
    public void run() {
        try {
            AbstractSpimData spimdata = (AbstractSpimData) command_service
                    .run(QuPathProjectToBDVDatasetCommand.class,true,
                            "quPathProject", qupath_project,
                            "unit", "MILLIMETER").get().getOutput("spimData");
            SourceAndConverter[] sacs =
                    sac_service.getSourceAndConverterFromSpimdata(spimdata)
                            .toArray(new SourceAndConverter[0]);

            if ((sacs!=null)&&(sacs.length>0)) { // Because the action could have been canceled
                mp.createSlice(sacs, slice_axis_initial, increment_between_slices, QuPathEntryEntity.class, new QuPathEntryEntity(-1));
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            mp.errorMessageForUser.accept("QuPath Import Error",
                    "QuPath project couldn't be imported.\n"+
                       "Check whether the project can be opened in QuPath (v0.2+), fix URI if necessary.\n"+
                       "Only (rotated) Bio-Formats image server are supported.");
            e.printStackTrace();
        }
    }

}
