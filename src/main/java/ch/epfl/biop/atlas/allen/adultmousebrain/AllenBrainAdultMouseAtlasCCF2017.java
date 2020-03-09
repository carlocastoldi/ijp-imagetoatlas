package ch.epfl.biop.atlas.allen.adultmousebrain;

import bdv.BigDataViewer;
import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.DisplayMode;
import ch.epfl.biop.atlas.BiopAtlas;
import ch.epfl.biop.atlas.allen.AllenAtlas;
import ch.epfl.biop.atlas.allen.AllenMap;
import ch.epfl.biop.atlas.allen.AllenOntology;
import ij.Prefs;
import net.imglib2.type.numeric.ARGBType;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.object.ObjectService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Atlas>Allen Brain Adult Mouse Brain CCF 2017")
public class AllenBrainAdultMouseAtlasCCF2017 extends AllenAtlas implements Command {

	public String toString() {
		return "Adult Mouse Brain - Allen Brain Atlas V3";
	}
	
	public static String keyPrefix = AllenBrainAdultMouseAtlasCCF2017.class.getName()+".";

	// AWS server : http://ec2-18-222-96-84.us-east-2.compute.amazonaws.com:8081/ccf_2017/
	static String defaultMapUrl = "http://ec2-18-222-96-84.us-east-2.compute.amazonaws.com:8081/ccf_2017/";//"file:/home/nico/Dropbox/BIOP/ABA/Data/new/ccf2017-mod65000.xml";
	@Parameter  
	String mapUrl = Prefs.get(keyPrefix+"mapUrl",defaultMapUrl);

	static String defaultOntologyUrl = "http://ec2-18-222-96-84.us-east-2.compute.amazonaws.com/1.json";//file:/home/nico/Dropbox/BIOP/ABA/BrainServerTest/1.json";
    @Parameter
    String ontologyUrl = Prefs.get(keyPrefix+"ontologyUrl",defaultOntologyUrl);

	@Parameter(type= ItemIO.OUTPUT)
	BiopAtlas ba;

	@Parameter
	ObjectService os;

	final static public int CHANNEL_AVERAGE = 0;

	final static public int CHANNEL_NISSL = 1;

	final static public int CHANNEL_LABELMOD65000 = 2;

	int previousId ;
	@Override
	public void run() {
        try {
			this.initialize(new URL(mapUrl), new URL(ontologyUrl));
			/*
			List<Integer> idList = ((AllenOntology)this.ontology).ontologyIdToParentId.keySet().stream().collect(Collectors.toList());
			Collections.sort(idList);

			previousId = 0;

			idList.stream().forEach(id -> {
				boolean identical = ((float)id==((float)previousId));
				if (identical) {
					System.out.println(((AllenOntology) this.ontology).ontologyIdToAcronym.get(id) + "\t" + id + "\t:\t" + (float) (id) + "\t ISSUE!!");
				} else {
					System.out.println(((AllenOntology) this.ontology).ontologyIdToAcronym.get(id) + "\t" + id + "\t:\t" + (float) (id) + "\t ok. ");

				}
				previousId = id;
			});*/

			((AllenOntology)this.ontology).mutateToModulo(65000); // Solves issue of very big indexes in allen brain ontology. The map has also been moduloed.
			((AllenMap)this.map).LabelChannel=2;
			this.map.show();

			//BigDataViewer bdv = ((AllenMap) this.map).bdv;

			//os.addObject(bdv);

			/*bdv.getViewer().getState().setDisplayMode(DisplayMode.FUSEDGROUP);
			bdv.getViewer().getVisibilityAndGrouping().setFusedEnabled(true);

			List<ConverterSetup> setups = bdv.getSetupAssignments().getConverterSetups();

			setups.get(CHANNEL_AVERAGE).setDisplayRange(0,255);
			setups.get(CHANNEL_NISSL).setDisplayRange(0,25000);
			setups.get(CHANNEL_LABELMOD65000).setDisplayRange(0,2000);

			setups.get(CHANNEL_AVERAGE).setColor(new ARGBType(ARGBType.rgba(255f,0f,0f,0f)));
			setups.get(CHANNEL_NISSL).setColor(new ARGBType(ARGBType.rgba(0f,255f,0f,0f)));
			setups.get(CHANNEL_LABELMOD65000).setColor(new ARGBType(ARGBType.rgba(0f,0f,255f,0f)));*/
			
	        Prefs.set(keyPrefix + "mapUrl", mapUrl);
	        Prefs.set(keyPrefix + "ontologyUrl", ontologyUrl);

			ba=this; // put current object to output -> then processed by plugin

        } catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}

}
