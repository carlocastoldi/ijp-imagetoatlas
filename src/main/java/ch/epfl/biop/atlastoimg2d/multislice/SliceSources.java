package ch.epfl.biop.atlastoimg2d.multislice;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.atlas.commands.ConstructROIsFromImgLabel;
import ch.epfl.biop.atlastoimg2d.commands.sourceandconverter.multislices.PutAtlasStructureToImageNoRoiManager;
import ch.epfl.biop.java.utilities.roi.ConvertibleRois;
import ch.epfl.biop.java.utilities.roi.types.IJShapeRoiArray;
import ch.epfl.biop.java.utilities.roi.types.ImageJRoisFile;
import ch.epfl.biop.java.utilities.roi.types.RealPointList;
import ch.epfl.biop.registration.Registration;
import ch.epfl.biop.registration.sourceandconverter.AffineTransformedSourceWrapperRegistration;
import ch.epfl.biop.registration.sourceandconverter.CenterZeroRegistration;
import ch.epfl.biop.scijava.command.ExportToImagePlusCommand;
import ij.ImagePlus;
import net.imglib2.RealPoint;
import net.imglib2.cache.img.DiskCachedCellImgFactory;
import net.imglib2.cache.img.DiskCachedCellImgOptions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import org.scijava.ui.behaviour.ClickBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;
import sc.fiji.bdvpg.services.SourceAndConverterServices;
import sc.fiji.bdvpg.sourceandconverter.SourceAndConverterAndTimeRange;
import sc.fiji.bdvpg.sourceandconverter.importer.EmptySourceAndConverterCreator;
import sc.fiji.bdvpg.sourceandconverter.transform.SourceResampler;
import sc.fiji.bdvpg.sourceandconverter.transform.SourceTransformHelper;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static net.imglib2.cache.img.DiskCachedCellImgOptions.options;

public class SliceSources {

    // What are they ?
    SourceAndConverter[] original_sacs;

    // Visible to the user in slicing mode
    SourceAndConverter[] relocated_sacs_positioning_mode;

    // Used for registration : like 3D, but tilt and roll ignored because it is handled on the fixed source side
    SourceAndConverter[] registered_sacs;

    Map<Registration, SourceAndConverter[]> registered_sacs_sequence = new HashMap<>();

    // Where are they ?
    double slicingAxisPosition;

    private boolean isSelected = false;

    double yShift_slicing_mode = 0;

    final MultiSlicePositioner mp;

    List<GraphicalHandle> ghs = new ArrayList<>();

    Behaviours behavioursHandleSlice;

    volatile AffineTransformedSourceWrapperRegistration zPositioner;

    volatile AffineTransformedSourceWrapperRegistration slicingModePositioner;

    CenterZeroRegistration centerPositioner;

    /*Set<Registration> pendingRegistrations = new HashSet<>();

    Set<Registration> lockedRegistrations = new HashSet<>();*/

    volatile ImagePlus impLabelImage = null;

    volatile AffineTransform3D at3DLastLabelImage = null;

    volatile boolean labelImageBeingComputed = false;

    volatile ConvertibleRois cvtRoisOrigin = null;

    volatile ConvertibleRois cvtRoisTransformed = null;

    // For fast display : Icon TODO : see https://github.com/bigdataviewer/bigdataviewer-core/blob/17d2f55d46213d1e2369ad7ef4464e3efecbd70a/src/main/java/bdv/tools/RecordMovieDialog.java#L256-L318
    protected SliceSources(SourceAndConverter[] sacs, double slicingAxisPosition, MultiSlicePositioner mp) {
        this.mp = mp;
        this.original_sacs = sacs;
        this.slicingAxisPosition = slicingAxisPosition;
        this.registered_sacs = this.original_sacs;

        centerPositioner = new CenterZeroRegistration();
        centerPositioner.setMovingImage(registered_sacs);

        zPositioner = new AffineTransformedSourceWrapperRegistration();

        behavioursHandleSlice = new Behaviours(new InputTriggerConfig());
        behavioursHandleSlice.behaviour(mp.getSelectedSourceDragBehaviour(this), "dragSelectedSources" + this.toString(), "button1");
        behavioursHandleSlice.behaviour((ClickBehaviour) (x, y) -> {
            deSelect();
            mp.bdvh.getViewerPanel().requestRepaint();
        }, "deselectedSources" + this.toString(), "button3", "ctrl button1");

        GraphicalHandle gh = new CircleGraphicalHandle(mp,
                behavioursHandleSlice,
                mp.bdvh.getTriggerbindings(),
                this.toString(), // pray for unicity ? TODO : do better than thoughts and prayers
                this::getBdvHandleCoords,
                this::getBdvHandleRadius,
                this::getBdvHandleColor
        );
        ghs.add(gh);
        iniPosition();
    }

    protected double getSlicingAxisPosition() {
        return slicingAxisPosition;
    }

    protected void setSlicingAxisPosition(double newSlicingAxisPosition) {
        slicingAxisPosition = newSlicingAxisPosition;
    }

    void iniPosition() {
        runRegistration(centerPositioner, Function.identity(), Function.identity());
        runRegistration(zPositioner, Function.identity(), Function.identity());
        waitForEndOfRegistrations();
        updatePosition();
    }

    public synchronized void select() {
        this.isSelected = true;
    }

    public synchronized void deSelect() {
        this.isSelected = false;
    }

    public boolean isSelected() {
        return this.isSelected;
    }

    private int currentSliceIndex = -1;

    public int getIndex() {
        return currentSliceIndex;
    }

    protected void setIndex(int idx) {
        currentSliceIndex = idx;
    }

    protected Integer[] getBdvHandleCoords() {
        AffineTransform3D bdvAt3D = new AffineTransform3D();
        mp.bdvh.getViewerPanel().state().getViewerTransform(bdvAt3D);
        RealPoint sliceCenter = new RealPoint(3);
        if (mp.currentMode == MultiSlicePositioner.POSITIONING_MODE) {
            sliceCenter = getCenterPositionPMode();
            bdvAt3D.apply(sliceCenter, sliceCenter);
            return new Integer[]{(int) sliceCenter.getDoublePosition(0), (int) sliceCenter.getDoublePosition(1), (int) sliceCenter.getDoublePosition(2)};
        } else if (mp.currentMode == MultiSlicePositioner.REGISTRATION_MODE) {
            RealPoint zero = new RealPoint(3);
            zero.setPosition(0, 0);
            bdvAt3D.apply(zero, zero);
            return new Integer[]{35 * (currentSliceIndex - mp.slices.size() / 2) + (int) zero.getDoublePosition(0), 20, 0};
        } else {
            return new Integer[]{0, 0, 0};
        }
    }

    /*protected String getRegistrationState(Registration registration) {
        if (lockedRegistrations.contains(registration)) {
            System.out.println("State asked - locked");
            return "(locked)";
        }
        if (registrations.contains(registration)) {
            System.out.println("State asked - done");
            return "(done)";
        }
        if (pendingRegistrations.contains(registration)) {
            System.out.println("State asked - pending");
            return "(pending)";
        }
        return "(!)";
    }*/

    protected String getActionState(CancelableAction action) {
        if (mapActionTask.containsKey(action)) {
            if (tasks.contains(mapActionTask.get(action))) {
                CompletableFuture future = tasks.get(tasks.indexOf(mapActionTask.get(action)));
                if (future.isDone()) {
                    return "(done)";
                } else if (future.isCancelled()) {
                    return "(cancelled)";
                } else {
                    return "(pending)";
                }
            } else {
                return "future not found";
            }
        } else {
            return "unknown action";
        }
    }

    public Integer[] getBdvHandleColor() {
        if (isSelected) {
            return new Integer[]{0, 255, 0, 200};

        } else {
            return new Integer[]{255, 255, 0, 64};
        }
    }

    public Integer getBdvHandleRadius() {
        return 12;
    }

    public void drawGraphicalHandles(Graphics2D g) {
        ghs.forEach(gh -> gh.draw(g));
    }

    public void disableGraphicalHandles() {
        ghs.forEach(gh -> gh.disable());
    }

    public void enableGraphicalHandles() {
        ghs.forEach(gh -> gh.enable());
    }

    protected boolean exactMatch(List<SourceAndConverter<?>> testSacs) {
        Set originalSacsSet = new HashSet();
        for (SourceAndConverter sac : original_sacs) {
            originalSacsSet.add(sac);
        }
        if (originalSacsSet.containsAll(testSacs) && testSacs.containsAll(originalSacsSet)) {
            return true;
        }
        Set transformedSacsSet = new HashSet();
        for (SourceAndConverter sac : relocated_sacs_positioning_mode) {
            transformedSacsSet.add(sac);
        }
        if (transformedSacsSet.containsAll(testSacs) && testSacs.containsAll(transformedSacsSet)) {
            return true;
        }

        return false;
    }

    protected boolean isContainingAny(Collection<SourceAndConverter<?>> sacs) {
        Set originalSacsSet = new HashSet();
        for (SourceAndConverter sac : original_sacs) {
            originalSacsSet.add(sac);
        }
        if (sacs.stream().distinct().anyMatch(originalSacsSet::contains)) {
            return true;
        }
        Set transformedSacsSet = new HashSet();
        for (SourceAndConverter sac : relocated_sacs_positioning_mode) {
            transformedSacsSet.add(sac);
        }
        if (sacs.stream().distinct().anyMatch(transformedSacsSet::contains)) {
            return true;
        }
        return false;
    }

    public void waitForEndOfRegistrations() {
        //if (registrationTasks!=null) {
        try {
            CompletableFuture.allOf(taskQueue).get();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Some registration were cancelled");
        }
        //}
    }

    protected void updatePosition() {
        AffineTransform3D zShiftAffineTransform = new AffineTransform3D();
        zShiftAffineTransform.translate(0, 0, slicingAxisPosition);
        zPositioner.setAffineTransform(zShiftAffineTransform); // Moves the registered slices to the correct position
        AffineTransform3D slicingModePositionAffineTransform = new AffineTransform3D();
        RealPoint center = getCenterPositionPMode();
        slicingModePositionAffineTransform.translate(center.getDoublePosition(0), center.getDoublePosition(1), -slicingAxisPosition);
        slicingModePositioner.setAffineTransform(slicingModePositionAffineTransform);
    }

    public RealPoint getCenterPositionPMode() {
        double slicingAxisSnapped = (((int) ((slicingAxisPosition) / mp.sizePixX)) * mp.sizePixX);
        double posX = (slicingAxisSnapped / mp.sizePixX * mp.sX / mp.reslicedAtlas.getStep()) + 0.5 * (mp.sX);
        double posY = mp.sY * yShift_slicing_mode;
        return new RealPoint(posX, posY, 0);
    }

    public RealPoint getCenterPositionRMode() {
        return new RealPoint(0, 0, slicingAxisPosition);
    }

    boolean processInProgress = false; // flag : true if a registration process is in progress

    private CompletableFuture<Boolean> taskQueue = CompletableFuture.completedFuture(true);

    private List<Registration> registrations = new ArrayList<>();

    // public : enqueueRegistration
    private boolean performRegistration(Registration<SourceAndConverter[]> reg,
                                       Function<SourceAndConverter[], SourceAndConverter[]> preprocessFixed,
                                       Function<SourceAndConverter[], SourceAndConverter[]> preprocessMoving) {

        reg.setFixedImage(preprocessFixed.apply(mp.reslicedAtlas.nonExtendedSlicedSources));
        reg.setMovingImage(preprocessMoving.apply(registered_sacs));
        boolean out = reg.register();
        if (!out) {

        } else {
            SourceAndConverterServices.getSourceAndConverterDisplayService()
                    .remove(mp.bdvh, registered_sacs);

            registered_sacs = reg.getTransformedImageMovingToFixed(registered_sacs);

            SourceAndConverterServices.getSourceAndConverterDisplayService()
                    .show(mp.bdvh, registered_sacs);

            slicingModePositioner = new AffineTransformedSourceWrapperRegistration();

            slicingModePositioner.setMovingImage(registered_sacs);
            SourceAndConverterServices.getSourceAndConverterService().remove(relocated_sacs_positioning_mode);

            relocated_sacs_positioning_mode = slicingModePositioner.getTransformedImageMovingToFixed(registered_sacs);
            updatePosition();

            registered_sacs_sequence.put(reg, registered_sacs);
        }
        return out;
    }

    /**
     * Asynchronous handling of registrations + combining with manual sequential registration if necessary
     *
     * @param reg
     */

    protected void runRegistration(Registration<SourceAndConverter[]> reg,
                                Function<SourceAndConverter[], SourceAndConverter[]> preprocessFixed,
                                Function<SourceAndConverter[], SourceAndConverter[]> preprocessMoving) {
        //pendingRegistrations.add(reg);
        //lockedRegistrations.add(reg);

        if (reg.isManual()) {
            System.out.println("Waiting for manual lock release...");
            synchronized (MultiSlicePositioner.manualActionLock) {
                System.out.println("Manual lock released.");
                //lockedRegistrations.remove(reg);
                //out =
                        performRegistration(reg,preprocessFixed, preprocessMoving);
            }
        } else {
            //lockedRegistrations.remove(reg);
            //out =
                    performRegistration(reg,preprocessFixed, preprocessMoving);
        }

        registrations.add(reg);

        processInProgress = false;
        mp.mso.updateInfoPanel(this);

    }

    // TODO
    protected void cancelCurrentRegistrations() {
        taskQueue.cancel(true);
    }

    public synchronized boolean removeRegistration(Registration reg) {
        if (false){//pendingRegistrations.contains(reg)) {
            System.out.println("Attempt to cancel current registrations...");
            cancelCurrentRegistrations();
            System.out.println("Attempt to cancel current registrations...");
            return true;
        }
        if (registrations.contains(reg)) {
            int idx = registrations.indexOf(reg);
            if (idx == registrations.size() - 1) {

                registrations.remove(reg);
                registered_sacs_sequence.remove(reg);

                Registration last = registrations.get(registrations.size() - 1);

                SourceAndConverterServices.getSourceAndConverterService()
                        .remove(registered_sacs);
                SourceAndConverterServices.getSourceAndConverterService()
                        .remove(relocated_sacs_positioning_mode);

                registered_sacs = registered_sacs_sequence.get(last);

                slicingModePositioner = new AffineTransformedSourceWrapperRegistration();

                slicingModePositioner.setMovingImage(registered_sacs);
                SourceAndConverterServices.getSourceAndConverterService().remove(relocated_sacs_positioning_mode);

                relocated_sacs_positioning_mode = slicingModePositioner.getTransformedImageMovingToFixed(registered_sacs);
                updatePosition();

                if (mp.currentMode.equals(MultiSlicePositioner.REGISTRATION_MODE)) {
                    SourceAndConverterServices.getSourceAndConverterDisplayService()
                            .show(mp.bdvh, registered_sacs);
                }
                if (mp.currentMode.equals(MultiSlicePositioner.POSITIONING_MODE)) {
                    SourceAndConverterServices.getSourceAndConverterDisplayService()
                            .show(mp.bdvh, relocated_sacs_positioning_mode);
                    enableGraphicalHandles();
                }

                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    List<CompletableFuture<Boolean>> tasks = new ArrayList<>();
    Map<CancelableAction, CompletableFuture> mapActionTask = new HashMap<>();

    protected void enqueueRunAction(CancelableAction action) {
        synchronized(tasks) {
            CompletableFuture<Boolean> startingPoint;
            if (tasks.size() == 0) {
                startingPoint = CompletableFuture.supplyAsync(() -> true);
            } else {
                startingPoint = tasks.get(tasks.size() - 1);
            }
            tasks.add(startingPoint.thenApplyAsync((out) -> {
                if (out == true) {
                    return action.run();
                } else {
                    return false;
                }
            }));
            mapActionTask.put(action, tasks.get(tasks.size() - 1));
        }
    }

    protected synchronized void enqueueCancelAction(CancelableAction action) {
        synchronized(tasks) {
            action.cancel();
        }
    }

    void computeLabelImage(AffineTransform3D at3D, String naming) {
        labelImageBeingComputed = true;

        System.out.println("Compute Label Image");

        final int[] cellDimensions = new int[]{32, 32, 1};

        // Cached Image Factory Options
        final DiskCachedCellImgOptions factoryOptions = options()
                .cellDimensions(cellDimensions)
                .cacheType(DiskCachedCellImgOptions.CacheType.BOUNDED)
                .maxCacheSize(1);

        // Creates cached image factory of Type UnsignedShort
        final DiskCachedCellImgFactory<UnsignedShortType> factory = new DiskCachedCellImgFactory<>(new UnsignedShortType(), factoryOptions);

        System.out.println("0");
        // 0 - slicing model : empty source but properly defined in space and resolution
        SourceAndConverter singleSliceModel = new EmptySourceAndConverterCreator("SlicingModel", at3D,
                mp.nPixX,
                mp.nPixY,
                1,
                factory
        ).get();

        SourceResampler resampler = new SourceResampler(null,
                singleSliceModel, false, false, false
        );

        AffineTransform3D translateZ = new AffineTransform3D();
        translateZ.translate(0, 0, -slicingAxisPosition);


        SourceAndConverter sac =
                mp.reslicedAtlas.nonExtendedSlicedSources[mp.reslicedAtlas.nonExtendedSlicedSources.length-1]; // By convention the label image is the last one

        sac = resampler.apply(sac);
        sac = SourceTransformHelper.createNewTransformedSourceAndConverter(translateZ, new SourceAndConverterAndTimeRange(sac, 0));

        ExportToImagePlusCommand export = new ExportToImagePlusCommand();

        export.level=0;
        export.timepointBegin=0;
        export.timepointEnd=0;
        export.sacs = new SourceAndConverter[1];
        export.sacs[0] = sac;
        export.run();

        impLabelImage = export.imp_out;
        //impLabelImage.show();

        ConstructROIsFromImgLabel labelToROIs = new ConstructROIsFromImgLabel();
        labelToROIs.atlas = mp.biopAtlas;
        labelToROIs.labelImg = impLabelImage;
        labelToROIs.smoothen = false;
        labelToROIs.run();
        cvtRoisOrigin = labelToROIs.cr_out;

        at3DLastLabelImage = at3D;
        labelImageBeingComputed = false;
    }

    public synchronized void export(String namingChoice, File dirOutput) {
        System.out.println("Export slice");
        // Need to raster the label image
        System.out.println("0");
        AffineTransform3D at3D = new AffineTransform3D();
        at3D.translate(-mp.nPixX / 2.0, -mp.nPixY / 2.0, 0);
        at3D.scale(mp.sizePixX, mp.sizePixY, mp.sizePixZ);
        at3D.translate(0, 0, slicingAxisPosition);

        System.out.println("0");
        boolean computeLabelImageNecessary = true;

        if (!labelImageBeingComputed) {
            if (at3DLastLabelImage != null) {
                if (Arrays.equals(at3D.getRowPackedCopy(), at3DLastLabelImage.getRowPackedCopy())) {
                    computeLabelImageNecessary = false;
                }
            }
        }

        System.out.println("1");
        if (computeLabelImageNecessary) {
            computeLabelImage(at3D, namingChoice);
        } else {
            while (labelImageBeingComputed) {
                try {
                    Thread.sleep(100);
                } catch (Exception e) {

                }
            }
        }

        System.out.println("2");
        computeTransformedRois();

        PutAtlasStructureToImageNoRoiManager roiRenamer = new PutAtlasStructureToImageNoRoiManager();
        roiRenamer.addAncestors=false;
        roiRenamer.addDescendants = true;
        roiRenamer.atlas = mp.biopAtlas;
        roiRenamer.structure_list = "997";
        roiRenamer.cr = cvtRoisOrigin;
        roiRenamer.namingChoice = namingChoice;
        roiRenamer.run();
        ConvertibleRois roiOutput = new ConvertibleRois();
        roiOutput.set(roiRenamer.output);

        System.out.println("3");
        ImageJRoisFile ijroisfile = (ImageJRoisFile) roiOutput.to(ImageJRoisFile.class);

        File f = new File(dirOutput, toString()+".zip");
        try {
            Files.move(Paths.get(ijroisfile.f.getAbsolutePath()),Paths.get(f.getAbsolutePath()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String toString() {
        int index = mp.getSortedSlices().indexOf(this);
        return "Slice_"+index;
    }

    public void computeTransformedRois() {
        while (!taskQueue.isDone()) {
            System.out.println("Waiting for registration to finish...");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("All registrations done ...");
        }

        cvtRoisTransformed = new ConvertibleRois();

        IJShapeRoiArray arrayIni = (IJShapeRoiArray) cvtRoisOrigin.to(IJShapeRoiArray.class);
        cvtRoisTransformed.set(arrayIni);
        RealPointList list = ((RealPointList) cvtRoisTransformed.to(RealPointList.class));
        // Perform reverse transformation, in the reverse order:
        //  - From atlas coordinates -> image coordinates

        AffineTransform3D at3D = new AffineTransform3D();
        at3D.translate(-mp.nPixX / 2.0, -mp.nPixY / 2.0, 0);
        at3D.scale(mp.sizePixX, mp.sizePixY, mp.sizePixZ);
        at3D.translate(0, 0, slicingAxisPosition);
        list = getTransformedPtsFixedToMoving(list, at3D.inverse());

        Collections.reverse(this.registrations);

        for (Registration reg : this.registrations) {
            //System.out.println("Registration class "+reg.getClass().getSimpleName());
            list = reg.getTransformedPtsFixedToMoving(list);
        }
        Collections.reverse(this.registrations);

        this.original_sacs[0].getSpimSource().getSourceTransform(0,0,at3D);
        list = getTransformedPtsFixedToMoving(list, at3D);

        cvtRoisTransformed.clear();
        list.shapeRoiList = new IJShapeRoiArray(arrayIni);
        cvtRoisTransformed.set(list);
    }

    public RealPointList getTransformedPtsFixedToMoving(RealPointList pts, AffineTransform3D at3d) {

        ArrayList<RealPoint> cvtList = new ArrayList<>();
        for (RealPoint p : pts.ptList) {
            RealPoint pt3d = new RealPoint(3);
            pt3d.setPosition(new double[]{p.getDoublePosition(0), p.getDoublePosition(1),0});
            at3d.inverse().apply(pt3d, pt3d);
            RealPoint cpt = new RealPoint(pt3d.getDoublePosition(0), pt3d.getDoublePosition(1));
            cvtList.add(cpt);
        }
        return new RealPointList(cvtList);
    }
}