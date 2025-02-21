package util;

import java.awt.Color;
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

	final static Color incompleteFeatureSet = new Color( 255, 128, 128 );
	final static Color completeFeatureSet = new Color( 128, 255, 128 );
	final static Color invalidFeatureSet = new Color( 128, 128, 128 );

	public List<Feature> featureList;
	public int numImages, numFeatures;
	public final ArrayList< ArrayList< FeatureState > > featuresState = new ArrayList<>();

	JLabel featureLabel, featureDescMinusOne, featureDescZero, featureDescPlusOne;
	JLabel placeholder1, placeholder2, barMinus1, barZero, barPlus1;
	JButton buttonMinus1, buttonZero, buttonPlus1, buttonPrevFeature, buttonNextFeature, buttonNextImage;

	int currentFeature = 0;

	public GUIStatePhase2( final MLTool tool )
	{
		super( tool );
	}

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
	public void notifyImageChanged()
	{
		updateFeature();
	}

	public boolean nextImageWithFeature( final FeatureState state )
	{
		for ( int i = currentImage() + 1; i < numImages; ++i )
			for ( int f = 0; f < numFeatures; ++f )
				if ( featuresState.get( i ).get( f ) == state )
				{
					// found it
					currentFeature = 0;
					sliderImg.setValue( i );

					return true;
				}

		return false;
	}

	public boolean prevImageWithFeature( final FeatureState state )
	{
		for ( int i = currentImage() - 1; i >= 0; --i )
			for ( int f = numFeatures - 1; f >= 0; --f )
				if ( featuresState.get( i ).get( f ) == state )
				{
					// found it
					currentFeature = 0;
					sliderImg.setValue( i );

					return true;
				}

		return false;
	}

	public boolean nextUnannotatedFeature()
	{
		for ( int i = currentImage(); i < numImages; ++i )
			for ( int f = currentFeature + 1; f < numFeatures; ++f )
				if ( featuresState.get( i ).get( f ) == FeatureState.NOT_ASSIGNED )
				{
					// found it
					currentFeature = f;

					if ( i != currentImage() )
						sliderImg.setValue( i );
					else
						updateFeature();

					return true;
				}

		return false;
	}

	public boolean prevUnannotatedFeature()
	{
		for ( int i = currentImage(); i >= 0; --i )
			for ( int f = currentFeature - 1; f >= 0; --f )
				if ( featuresState.get( i ).get( f ) == FeatureState.NOT_ASSIGNED )
				{
					// found it
					currentFeature = f;

					if ( i != currentImage() )
						sliderImg.setValue( i );
					else
						updateFeature();

					return true;
				}

		return false;
	}

	public void nextFeature()
	{
		if ( currentFeature < numFeatures - 1 )
		{
			++currentFeature;
			updateFeature();
		}
		else
		{
			if ( currentImage() < numImages - 1 )
			{
				currentFeature = 0;
				nextImage();
			}
		}
	}

	public void prevFeature()
	{
		if ( currentFeature > 0 )
		{
			--currentFeature;
			updateFeature();
		}
		else
		{
			if ( currentImage() > 0 )
			{
				currentFeature = numFeatures - 1;
				prevImage();
			}
		}
	}

	public void updateFeature()
	{
		featureLabel.setText( featureLabel() );
		featureDescMinusOne.setText( featureDescMinus1() );
		featureDescZero.setText( featureDescZero() );
		featureDescPlusOne.setText( featureDescPlus1() );

		updateFeatureState();
	}

	public void updateFeatureState()
	{
		final FeatureState state = featureState();

		//System.out.println( "current image: " + currentImage() + " state=" + state);
		//System.out.println( "feature index: " + currentFeature);
		//System.out.println( "feature state: " + state);

		barMinus1.setBackground( state == FeatureState.NEGATIVE ? Color.magenta : Color.lightGray );
		barZero.setBackground( state == FeatureState.ZERO ? Color.magenta : Color.lightGray );
		barPlus1.setBackground( state == FeatureState.POSITIVE ? Color.magenta : Color.lightGray );

		testAndUpdateFeatureComplete();
	}

	public void setAllFeatureStatesInvalid()
	{
		for ( int i = 0; i < numFeatures; ++i )
			featuresState.get( currentImage() ).set( i, FeatureState.INVALID );

		currentFeature = 0;
		nextImage();
	}

	public void setFeatureState( final FeatureState state )
	{
		featuresState.get( currentImage() ).set( currentFeature, state );
		updateFeatureState();
	}

	public boolean testAndUpdateFeatureComplete()
	{
		boolean complete = true;
		boolean invalid = false;

		for ( int i = 0; i < numFeatures; ++i )
		{
			final FeatureState state = featuresState.get( currentImage() ).get( i );

			if ( state == FeatureState.INVALID )
				invalid = true;

			if ( state != FeatureState.NEGATIVE && state != FeatureState.ZERO && state != FeatureState.POSITIVE )
				complete = false;
		}

		if ( invalid )
			featureLabel.setBackground( invalidFeatureSet );
		else if ( complete )
			featureLabel.setBackground( completeFeatureSet );
		else
			featureLabel.setBackground( incompleteFeatureSet );

		return complete;
	}
	
	public FeatureState featureState() { return featuresState.get( currentImage() ).get( currentFeature ); }
	public String featureLabel() { return "Feature " + (currentFeature+1) + "/" + featureList.size() + ": " + featureList.get( currentFeature ).name; }
	public String featureDescMinus1() { return "-: " + featureList.get( currentFeature ).minusOne; }
	public String featureDescZero() { return "0: " + featureList.get( currentFeature ).zero; }
	public String featureDescPlus1() { return "+: " + featureList.get( currentFeature ).plusOne; }

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
