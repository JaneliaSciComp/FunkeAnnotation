package util;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JLabel;

import org.janelia.saalfeldlab.n5.GsonUtils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import ij.IJ;

public class GUIStatePhase2 extends GUIState
{
	// the state of the GUI when rating images
	public enum FeatureState { NEGATIVE, ZERO, POSITIVE, NOT_ASSIGNED, INVALID }

	public List<Feature> featureList;
	public int numImages, numFeatures;
	public final ArrayList< ArrayList< FeatureState > > featuresState = new ArrayList<>();

	JLabel featureLabel, featureDescMinusOne, featureDescZero, featureDescPlusOne;
	JLabel placeholder1, placeholder2, barMinus1, barZero, barPlus1;
	JButton buttonMinus1, buttonZero, buttonPlus1, buttonPrevFeature, buttonNextFeature, buttonNextImage;

	public boolean setup( final int numImages, final String json )
	{
		featureList = loadJSON( json );

		if ( featureList == null )
		{
			IJ.log( "failed to load json: " + json );
			return false;
		}

		IJ.log( "Loaded " + featureList.size() + " features from json ... ");

		this.numImages = numImages;
		this.numFeatures = featureList.size();

		for ( int i = 0; i < numImages; ++i )
		{
			final ArrayList< FeatureState > states = new ArrayList<>( numFeatures );

			for ( int j = 0; j < numFeatures; ++j )
				states.add( FeatureState.NOT_ASSIGNED );

			this.featuresState.add( states );
		}

		return true;
	}

	@Override
	public boolean save(String dir) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean load(String dir) {
		// TODO Auto-generated method stub
		return false;
	}

	// just for loading ...
	public static class Feature
	{
		final String name;
		String minusOne, zero, plusOne;

		public Feature( String name )
		{
			this.name = name;
		}
	}

	public static List< Feature > loadJSON( final String json )
	{
		try 
		{
			final Gson gson = new Gson();
			final JsonReader reader = new JsonReader(new FileReader( json ));
			final JsonElement element = gson.fromJson(reader, JsonElement.class );

			final Map<String, Class<?>> sc = GsonUtils.listAttributes( element );
			final JsonObject jo = element.getAsJsonObject();

			final List< Feature > featureList = new ArrayList<>();

			sc.forEach( (s,c) -> 
			{
				IJ.log( "\nFeature: " + s + " [class=" + c + "]");

				final JsonObject featureElement = jo.getAsJsonObject( s );

				final Feature feature = new Feature( s );

				feature.minusOne = featureElement.get( "-1" ).getAsString();
				feature.zero = featureElement.get( "0" ).getAsString();
				feature.plusOne = featureElement.get( "1" ).getAsString();

				featureList.add( feature );

				IJ.log( "-1: " + feature.minusOne );
				IJ.log( "0: " + feature.zero );
				IJ.log( "+1 :" + feature.plusOne );

				//featureElement.entrySet().forEach( e -> System.out.println(e.getKey() + ": " + e.getValue().getAsString()));//forEach( (s1,e1) -> System.out.println( s1 ) );
				//System.out.println();
			} );

			return featureList;
		}
		catch (FileNotFoundException e1)
		{
			e1.printStackTrace();
			return null;
		}
	}
}
