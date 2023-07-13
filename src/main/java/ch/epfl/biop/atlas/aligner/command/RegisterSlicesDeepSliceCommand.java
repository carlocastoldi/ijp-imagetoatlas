package ch.epfl.biop.atlas.aligner.command;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.atlas.aligner.LockAndRunOnceSliceAction;
import ch.epfl.biop.atlas.aligner.MoveSliceAction;
import ch.epfl.biop.atlas.aligner.MultiSlicePositioner;
import ch.epfl.biop.atlas.aligner.RegisterSliceAction;
import ch.epfl.biop.atlas.aligner.SliceSources;
import ch.epfl.biop.atlas.aligner.plugin.IABBARegistrationPlugin;
import ch.epfl.biop.quicknii.QuickNIIExporter;
import ch.epfl.biop.quicknii.QuickNIISeries;
import ch.epfl.biop.quicknii.QuickNIISlice;
import ch.epfl.biop.registration.Registration;
import ch.epfl.biop.registration.sourceandconverter.affine.AffineRegistration;
import ch.epfl.biop.sourceandconverter.processor.SourcesAffineTransformer;
import ch.epfl.biop.sourceandconverter.processor.SourcesChannelsSelect;
import ch.epfl.biop.sourceandconverter.processor.SourcesProcessor;
import ch.epfl.biop.sourceandconverter.processor.SourcesProcessorHelper;
import ij.IJ;
import ij.gui.WaitForUserDialog;
import net.imglib2.realtransform.AffineTransform3D;
import org.scijava.Context;
import org.scijava.InstantiableException;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.PluginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/**
 * Command which is using the amazing DeepSlice workflow by Harry Carey, and William Redmond, in Simon McMullan group
 * (https://researchers.mq.edu.au/en/publications/deepslice-a-deep-neural-network-for-fully-automatic-alignment-of-)
 * You will need an internet connection (https://www.deepslice.com.au/)
 * in order to use this command, and there's some manual interaction
 * required (but it's worth!)
 * Contrary to other registration command, the slices are not registered independently, because
 * it's easier to drag many files at once in the DeepSlice web interface.
 * It is possible to forbid the angle adjustement.
 * Contrary to other registration methods, DeepSlice can help defining the location in Z of slices.
 * However, sometimes, DeepSlice is swapping slices incorrectly. So there is an option that maintains the
 * slices order in the process. Briefly, if this options is checked, the slice with the biggest difference
 * of rank after DeepSlice registration is moved until no difference of rank exist.
 * DeepSlice provides the fastest way to have an initial:
 * - correct positioning in Z
 * - slicing angle
 * - affine in-plane registration
 * By default, ABBA downsamples to 30 microns per pixel for DeepSlice and saves as an 8 bit jpeg image.
 * Make sure you have multiresolution files if you don't want your downscaling to look bad! Also
 * this is currently the only registration method where the display settings matter for the registration.
 *
 */

// TODO: allow only in coronal and AB atlas
// TODO : fix display settings not updated with the new ABBA structure

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class,
        menuPath = "Plugins>BIOP>Atlas>Multi Image To Atlas>Align>ABBA - DeepSlice Registration",
        description = "Uses Deepslice Web interface for affine in plane and axial registration of selected slices")
public class RegisterSlicesDeepSliceCommand implements Command {

    static Logger logger = LoggerFactory.getLogger(RegisterSlicesDeepSliceCommand.class);

    @Parameter(visibility = ItemVisibility.MESSAGE)
    public String message = "<html><b>Don't forget to adjust min/max display settings!</b> <br>  Almost 50% of images sent by ABBA users to DeepSlice are over-saturated. <br> (and thus, badly registered) </html>";

    @Parameter
    MultiSlicePositioner mp;

    @Parameter
    PlatformService ps;

    @Parameter
    Context ctx;

    @Parameter
    PluginService pluginService;

    @Parameter(label = "Slices channels, 0-based, comma separated, '*' for all channels", description = "'0,2' for channels 0 and 2")
    String channels = "*";

    @Parameter(label = "Section Name Prefix")
    String image_name_prefix = "Section";

    @Parameter(label = "QuickNII dataset folder", style="directory")
    File dataset_folder;

    @Parameter(label = "Allow change of atlas slicing angle")
    boolean allow_slicing_angle_change = true;

    @Parameter(label = "Allow change of position along the slicing axis")
    boolean allow_change_slicing_position = true;

    @Parameter(label = "Maintain the rank of the slices")
    boolean maintain_slices_order = true;

    @Parameter(label = "Affine transform in plane")
    boolean affine_transform = true;

    //@Parameter(label = "pixel size in micrometer")
    double px_size_micron = 30;

    //@Parameter(label = "Convert to 8 bit image")
    boolean convert_to_8_bits = false;

    //@Parameter(label = "Convert to jpg (single channel recommended)")
    boolean convert_to_jpg = true;

    //@Parameter
    boolean interpolate = false;

    @Parameter(required = false, persist = false)
    Function<File,File> deepSliceProcessor = null;

    @Override
    public void run() {
        List<SliceSources> slicesToRegister = mp.getSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());

        if (slicesToRegister.size() == 0) {
            mp.log.accept("No slice selected");
            mp.warningMessageForUser.accept("No selected slice", "Please select the slice(s) you want to register");
            return;
        }

        Map<SliceSources, Holder<Double>> newAxisPosition = new HashMap<>();
        Map<SliceSources, Holder<Registration<SourceAndConverter<?>[]>>> newSliceRegistration = new HashMap<>();

        for (SliceSources slice: slicesToRegister) {
            newAxisPosition.put(slice, new Holder<>());
            newSliceRegistration.put(slice, new Holder<>());
        }

        Supplier<Boolean> deepSliceRunner =  () -> {

            exportDownsampledDataset(slicesToRegister);

            File deepSliceResult;

            if (deepSliceProcessor == null) {
                IJ.log("Dataset exported in folder " + dataset_folder.getAbsolutePath());
                new WaitForUserDialog("Now opening DeepSlice webpage",
                        "Drag and drop all slices into the webpage.")
                        .show();
                try {
                    ps.open(new URL("https://www.deepslice.com.au/"));
                    ps.open(dataset_folder.toURI().toURL());
                } catch (Exception e) {
                    mp.errorMessageForUser.accept("Couldn't open DeepSlice from Fiji, ",
                            "please go to https://www.deepslice.com.au/ and drag and drop your images located in " + dataset_folder.getAbsolutePath());
                }
                new WaitForUserDialog("DeepSlice result",
                        "Put the 'results.xml' file into " + dataset_folder.getAbsolutePath() + " then press ok.")
                        .show();
                try {
                    Thread.sleep(7000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                deepSliceResult = new File(dataset_folder, "results.xml");
            } else {
                deepSliceResult = deepSliceProcessor.apply(dataset_folder);//, "results.xml");
            }

            if (!deepSliceResult.exists()) {
                mp.errorMessageForUser.accept("Deep Slice registration aborted",
                        "Could not find DeepSlice result file " + deepSliceResult.getAbsolutePath()
                );
                return false;
            }

            // Ok, now comes the big deal. First, real xml file

            try {
                JAXBContext context = JAXBContext.newInstance(QuickNIISeries.class);
                Unmarshaller unm = context.createUnmarshaller();
                unm.setEventHandler(new jakarta.xml.bind.helpers.DefaultValidationEventHandler());
                series = (QuickNIISeries) unm.unmarshal(new FileReader(deepSliceResult.getAbsolutePath()));
            } catch (Exception e) {
                mp.errorMessageForUser.accept("Deep Slice Command error", "Could not parse xml file " + deepSliceResult.getAbsolutePath());
                e.printStackTrace();
                return false;
            }

            if (series.slices.length != slicesToRegister.size()) {
                mp.errorMessageForUser.accept("Deep Slice Command error", "Please retry the command, DeepSlice returned less images than present in the input (" + (slicesToRegister.size() - series.slices.length) + " missing) ! ");
                return false;
            }

            double nPixX = 1000.0 * mp.getROI()[2] / px_size_micron;
            double nPixY = 1000.0 * mp.getROI()[3] / px_size_micron;

            if (allow_slicing_angle_change) {
                logger.debug("Slices pixel number = " + nPixX + " : " + nPixY);
                adjustSlicingAngle(10, slicesToRegister, nPixX, nPixY); //
            }

            if (allow_change_slicing_position) {
                adjustSlicesZPosition(slicesToRegister, nPixX, nPixY, newAxisPosition);
            }

            if (affine_transform) {
                try {
                    affineTransformInPlane(slicesToRegister, nPixX, nPixY, newSliceRegistration);//, newSliceAffineTransformer);
                } catch (InstantiableException e) {
                    e.printStackTrace();
                }
            }
            return true;
        };
        AtomicInteger counter = new AtomicInteger();
        counter.set(0);

        AtomicBoolean result = new AtomicBoolean();
        for (SliceSources slice: slicesToRegister) {
            new LockAndRunOnceSliceAction(mp, slice, counter, slicesToRegister.size(), deepSliceRunner, result).runRequest(true);
            if (allow_change_slicing_position) {
                new MoveSliceAction(mp, slice, newAxisPosition.get(slice)).runRequest(true);
            }
            if (affine_transform) {
                Holder<Registration<SourceAndConverter<?>[]>> regSupplier = newSliceRegistration.get(slice);
                new RegisterSliceAction(mp, slice, regSupplier,
                        SourcesProcessorHelper.Identity(),
                        SourcesProcessorHelper.Identity()).runRequest(true);
            }

        }
    }

    QuickNIISeries series;

    private void adjustSlicingAngle(int nIterations, List<SliceSources> slices, double nPixX, double nPixY) {

        double oldX = mp.getReslicedAtlas().getRotateX();
        double oldY = mp.getReslicedAtlas().getRotateY();

        for (int nAdjust = 0;nAdjust<nIterations;nAdjust++) { // Iterative rotation adjustement, because that's convenient

            AffineTransform3D toABBA = mp.getReslicedAtlas().getSlicingTransformToAtlas().inverse();

            // Transform sources according to anchoring
            double[] rxs = new double[slices.size()];
            double[] rys = new double[slices.size()];

            for (int i = 0; i < slices.size(); i++) {
                QuickNIISlice slice = series.slices[i];

                AffineTransform3D toCCFv3 = QuickNIISlice.getTransformInCCFv3(slice,nPixX,nPixY);

                AffineTransform3D nonFlat = toCCFv3.preConcatenate(toABBA);

                // Get the z vector to measure the angle of rotation compared to the actual one

                double zx = nonFlat.get(2, 0);
                double zy = nonFlat.get(2, 1);
                double zz = nonFlat.get(2, 2);

                double zNorm = Math.sqrt(zx * zx + zy * zy + zz * zz);

                zx /= zNorm;
                zy /= zNorm;
                zz /= zNorm;

                double ry = Math.asin(zx);
                double rx = Math.asin(zy);

                rxs[i] = rx;
                rys[i] = ry;

            }

            logger.debug("Round "+nAdjust);
            logger.debug("rotation x =" + getMedian(rxs));
            logger.debug("rotation y =" + getMedian(rys));
            mp.getReslicedAtlas().setRotateY(mp.getReslicedAtlas().getRotateY() - getMedian(rys) / 2.0);
            mp.getReslicedAtlas().setRotateX(mp.getReslicedAtlas().getRotateX() + getMedian(rxs) / 2.0);
        }

        String angleUpdatedMessage = "";

        DecimalFormat df = new DecimalFormat("#0.000");
        angleUpdatedMessage+="Angle X : "+oldX+" has been updated to "+df.format(mp.getReslicedAtlas().getRotateX())+"\n";

        angleUpdatedMessage+="Angle Y : "+oldY+" has been updated to "+df.format(mp.getReslicedAtlas().getRotateY())+"\n";

        mp.log.accept("Slicing angle adjusted to "+ angleUpdatedMessage);

    }

    private void adjustSlicesZPosition(final List<SliceSources> slices, double nPixX, double nPixY, Map<SliceSources, Holder<Double>> newAxisPosition) {
        // The "slices" list is sorted according to the z axis, before deepslice action

        final String regex = "(.*)"+image_name_prefix +"_s([0-9]+).*";
        final Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);

        Map<SliceSources, Double> slicesNewPosition = new HashMap<>();

        AffineTransform3D toABBA = mp.getReslicedAtlas().getSlicingTransformToAtlas().inverse();
        for (int i = 0; i < slices.size(); i++) {
            QuickNIISlice slice = series.slices[i];

            final Matcher matcher = pattern.matcher(slice.filename);

            matcher.find();

            int iSliceSource = Integer.parseInt(matcher.group(2));

            logger.debug("Slice QuickNii "+i+" correspond to initial slice "+iSliceSource);

            AffineTransform3D toCCFv3 = QuickNIISlice.getTransformInCCFv3(slice, nPixX, nPixY);

            AffineTransform3D nonFlat = toCCFv3.preConcatenate(toABBA);

            double zLocation = nonFlat.get(2,3);

            slicesNewPosition.put(slices.get(iSliceSource), zLocation);
        }

        Map<Integer, SliceSources> mapNewRankToSlices = new HashMap<>();
        if (maintain_slices_order) {
            // We should swap the position of the one slice with the biggest rank difference until there's no rank difference
            int biggestRankDifference = -1;
            int indexOfSliceWithBiggestRankDifference = -1;
            int targetIndex = -1;
            int direction = 0;

            while (biggestRankDifference!=0) {
                Integer[] indicesNewlyOrdered = new Integer[slices.size()];

                for (int i = 0; i < indicesNewlyOrdered.length; i++) {
                    indicesNewlyOrdered[i] = i;
                }

                Arrays.sort(indicesNewlyOrdered, Comparator.comparingDouble(i -> slicesNewPosition.get(slices.get(i))));

                for (int i = 0; i < indicesNewlyOrdered.length; i++) {
                    mapNewRankToSlices.put(i,slices.get(indicesNewlyOrdered[i]));
                }
                biggestRankDifference = 0;
                for (int i = 0; i < indicesNewlyOrdered.length; i++) {
                    int abs = Math.abs(i - indicesNewlyOrdered[i]);
                    if (abs > biggestRankDifference) {
                        biggestRankDifference = abs;
                        indexOfSliceWithBiggestRankDifference = indicesNewlyOrdered[i];
                        targetIndex = indicesNewlyOrdered[i];
                        direction = i - indicesNewlyOrdered[i];
                    }
                }
                if (biggestRankDifference!=0) { // Why move anything if everything is alright ?
                    // Moving slice indexOfSliceWithBiggestRankDifference to a new rank targetIndex
                    double targetLocation = slicesNewPosition.get(mapNewRankToSlices.get(targetIndex)); // NPE !!
                    if (direction < 0) targetLocation += mp.getAtlas().getMap().getAtlasPrecisionInMillimeter()/10.0;
                    if (direction > 0) targetLocation -= mp.getAtlas().getMap().getAtlasPrecisionInMillimeter()/10.0;
                    slicesNewPosition.put(slices.get(indexOfSliceWithBiggestRankDifference), targetLocation);
                }
            }

        }

        for (SliceSources slice : slices) {
            newAxisPosition.get(slice).accept(slicesNewPosition.get(slice));
        }
    }

    private void  affineTransformInPlane(final List<SliceSources> slices, double nPixX, double nPixY,
                                         Map<SliceSources, Holder<Registration<SourceAndConverter<?>[]>>> newSliceTransfomr
    //                                     Map<SliceSources, Holder<SourcesAffineTransformer>> newSAT
    ) throws InstantiableException {
        AffineTransform3D toABBA = mp.getReslicedAtlas().getSlicingTransformToAtlas().inverse();

        // Transform sources according to anchoring
        final String regex = "(.*)"+image_name_prefix +"_s([0-9]+).*";
        final Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);

        for (int i = 0; i < slices.size(); i++) {
            QuickNIISlice slice = series.slices[i];

            AffineTransform3D toCCFv3 = QuickNIISlice.getTransformInCCFv3(slice,nPixX,nPixY);

            AffineTransform3D flat = toCCFv3.preConcatenate(toABBA);

            // Removes any z transformation -> in plane transformation
            flat.set(0,2,0);
            flat.set(0,2,1);
            flat.set(1,2,2);
            flat.set(0,2,3);

            // flat gives the good registration result for an image which is located at 0,0,0, and
            // which has a pixel size of 1
            // We need to transform the original image this way

            AffineTransform3D preTransform = new AffineTransform3D();
            preTransform.scale(1000.0/px_size_micron);
            preTransform.set(1,2,2);
            preTransform.set(-1000.0/px_size_micron*mp.getROI()[0], 0, 3);
            preTransform.set(-1000.0/px_size_micron*mp.getROI()[1], 1, 3);

            // if pixel size micron is 1 -> scaling factor = 1000
            // if pixel size is 1000 micron -> scaling factor = 1

            final Matcher matcher = pattern.matcher(slice.filename);

            matcher.find();

            int iSliceSource = Integer.parseInt(matcher.group(2));

            logger.debug("Slice QuickNii "+i+" correspond to initial slice "+iSliceSource);

            IABBARegistrationPlugin registration = (IABBARegistrationPlugin)
                    pluginService.getPlugin(AffineRegistration.class).createInstance();
            registration.setScijavaContext(ctx);

            registration.setSliceInfo(new MultiSlicePositioner.SliceInfo(mp, slices.get(iSliceSource)));
            Map<String,Object> parameters = new HashMap<>();

            AffineTransform3D inPlaneTransform = new AffineTransform3D();
            inPlaneTransform.set(flat);
            inPlaneTransform.concatenate(preTransform);

            parameters.put("transform", AffineRegistration.affineTransform3DToString(inPlaneTransform));
            // Always set slice at zero position for registration
            parameters.put("pz", 0);
            AffineTransform3D at3d = new AffineTransform3D();
            at3d.translate(0,0,-slices.get(iSliceSource).getSlicingAxisPosition());
            SourcesAffineTransformer z_zero = new SourcesAffineTransformer(at3d);

            // Sends parameters to the registration
            registration.setRegistrationParameters(MultiSlicePositioner.convertToString(ctx,parameters));

            newSliceTransfomr.get(slices.get(iSliceSource)).accept( registration );
            //newSAT.get(slices.get(iSliceSource)).accept( z_zero );

        }

    }

    private void exportDownsampledDataset(List<SliceSources> slices) {

        SourcesProcessor preprocess = SourcesProcessorHelper.Identity();

        if (!channels.trim().equals("*")) {
            List<Integer> indices = Arrays.stream(channels.trim().split(",")).mapToInt(Integer::parseInt).boxed().collect(Collectors.toList());

            int maxIndex = indices.stream().mapToInt(e -> e).max().getAsInt();

            if (maxIndex>=mp.getChannelBoundForSelectedSlices()) {
                mp.log.accept("Missing channel in selected slice(s).");
                mp.errlog.accept("Missing channel in selected slice(s)\n One selected slice only has "+mp.getChannelBoundForSelectedSlices()+" channel(s).\n Maximum index : "+(mp.getChannelBoundForSelectedSlices()-1) );
                return;
            }

            preprocess = new SourcesChannelsSelect(indices);
        }

        try {

            QuickNIIExporter.builder()
                    .roi(mp.getROI())
                    .cvt8bits(convert_to_8_bits)
                    .jpeg(convert_to_jpg)
                    .setProcessor(preprocess)
                    .slices(slices)
                    .name(image_name_prefix)
                    .folder(dataset_folder)
                    .pixelSizeMicron(px_size_micron)
                    .interpolate(interpolate)
                    .create()
                    .export();

        } catch (Exception e) {
            mp.errorMessageForUser.accept("Export to Quick NII dataset error. ", e.getMessage());
        }
    }

    public static double getMedian(double[] array) {
        Arrays.sort(array);
        double median;
        if (array.length % 2 == 0)
            median = (array[array.length/2] + array[array.length/2 - 1])/2;
        else
            median = array[array.length/2];
        return median;
    }

    public static class Holder<T> implements Supplier<T>, Consumer<T> {
        T t;
        public Holder(T t) {
            this.t = t;
        }

        public Holder() {

        }

        public T get() {
            return t;
        }

        @Override
        public void accept(T t) {
            this.t = t;
        }
    }

}
