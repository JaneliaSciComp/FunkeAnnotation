package util;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.janelia.saalfeldlab.n5.GsonUtils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ColorProcessor;
import net.imglib2.type.numeric.ARGBType;

public class GUIStatePhase2 extends GUIState
{
	// the state of the GUI when rating images
	public enum FeatureState { NEGATIVE, ZERO, POSITIVE, NOT_ASSIGNED, INVALID }

	public static boolean showGlobalProgress = false;

	static Color incompleteFeatureSet = new Color( 255, 128, 128 );
	static Color completeFeatureSet = new Color( 128, 255, 128 );
	static Color invalidFeatureSet = new Color( 128, 128, 128 );

	static String state = "annotation_state.txt";
	static String csv = "results.csv";
	static String csvSplitBy = ",";

	public List<Feature> featureList;
	public int numImages, numFeatures;
	public final ArrayList< ArrayList< FeatureState > > featuresState = new ArrayList<>();

	public ImagePlus overview;

	JLabel featureLabel, featureDescMinusOne, featureDescZero, featureDescPlusOne;
	JLabel placeholder1, placeholder2, barMinus1, barZero, barPlus1;
	JButton buttonMinus1, buttonZero, buttonPlus1, buttonPrevFeature, buttonNextFeature, buttonNextImage;

	final boolean globalProgress;
	int currentFeature = 0;
	private Phase2Progress progress = null;

	public GUIStatePhase2( final MLTool tool, final boolean globalProgress )
	{
		super( tool );
		this.globalProgress = globalProgress;
	}

	public void register( final Phase2Progress progress )
	{
		this.progress = progress;
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

		if ( globalProgress )
		{
			final ColorProcessor overviewP = new ColorProcessor( numImages, numFeatures );
			overview = new ImagePlus( "Global Annotation Progress", overviewP );
			reDrawGlobalProgress();
			overview.show();
		}

		return true;
	}

	@Override
	public void notifyImageChanged()
	{
		updateFeature();
	}

	public void reDrawGlobalProgress()
	{
		if ( !globalProgress )
			return;

		int index = 0;
		final ColorProcessor cp = (ColorProcessor)overview.getProcessor();

		for ( int y = 0; y < numFeatures; ++y )
			for ( int x = 0; x < numImages; ++x )
				cp.set(index++, getColor( featuresState.get( x ).get( y ) ));
	}

	public static int getColor( final FeatureState myState )
	{
		final int r,g,b;

		if ( myState == FeatureState.INVALID )
		{
			r = invalidFeatureSet.getRed();
			g = invalidFeatureSet.getGreen();
			b = invalidFeatureSet.getBlue();
		}
		else if ( myState == FeatureState.NOT_ASSIGNED )
		{
			r = incompleteFeatureSet.getRed();
			g = incompleteFeatureSet.getGreen();
			b = incompleteFeatureSet.getBlue();
		}
		else
		{
			r = completeFeatureSet.getRed();
			g = completeFeatureSet.getGreen();
			b = completeFeatureSet.getBlue();
		}

		return ARGBType.rgba(r, g, b, 0);
	}

	public void updateGlobalProgress()
	{
		if ( !globalProgress )
			return;

		final ColorProcessor cp = (ColorProcessor)overview.getProcessor();

		cp.set( currentImage(), currentFeature, getColor( featureState() ) );

		overview.updateAndDraw();
	}

	public boolean nextImageWithFeature( final FeatureState state )
	{
		return nextImageWithFeature( Collections.singletonList( state ) );
	}

	public boolean nextImageWithFeature( final List< FeatureState > states )
	{
		for ( int i = currentImage() + 1; i < numImages; ++i )
			for ( int f = 0; f < numFeatures; ++f )
			{
				final FeatureState myState = featuresState.get( i ).get( f );

				boolean present = false;

				for ( final FeatureState s : states )
					present |= ( s == myState );

				if ( present )
				{
					// found it
					currentFeature = 0;
					sliderImg.setValue( i );

					return true;
				}
			}

		return false;
	}

	public boolean prevImageWithFeature( final FeatureState state )
	{
		return prevImageWithFeature( Collections.singletonList( state ) );
	}

	public boolean prevImageWithFeature( final List< FeatureState > states )
	{
		for ( int i = currentImage() - 1; i >= 0; --i )
			for ( int f = numFeatures - 1; f >= 0; --f )
			{
				final FeatureState myState = featuresState.get( i ).get( f );

				boolean present = false;

				for ( final FeatureState s : states )
					present |= ( s == myState );

				if ( present )
				{
					// found it
					currentFeature = 0;
					sliderImg.setValue( i );

					return true;
				}
			}

		return false;
	}

	public boolean nextFeature( final FeatureState state )
	{
		return nextFeature( Collections.singletonList( state ) );
	}

	public boolean nextFeature( final List< FeatureState > states )
	{
		//System.out.println();
		int startFeature = currentFeature + 1;

		for ( int i = currentImage(); i < numImages; ++i )
		{
			// only on the current image start one further, then start at 0
			for ( int f = startFeature; f < numFeatures; ++f )
			{
				//System.out.println( i+","+f+": " + featuresState.get( i ).get( f ) );
				final FeatureState myState = featuresState.get( i ).get( f );

				boolean present = false;

				for ( final FeatureState s : states )
					present |= ( s == myState );

				if ( present )
				{
					// found it
					currentFeature = f;

					if ( i != currentImage() )
						sliderImg.setValue( i );
					else
						updateFeature();

					return true;
				}
			}

			startFeature = 0;
		}

		return false;
	}

	public boolean prevFeature( final FeatureState state )
	{
		return prevFeature( Collections.singletonList( state ) );
	}

	public boolean prevFeature( final List< FeatureState > states )
	{
		int startFeature = currentFeature - 1;

		for ( int i = currentImage(); i >= 0; --i )
		{
			// only on the current image start one feature down from the current one, then start at the highest
			for ( int f = startFeature; f >= 0; --f )
			{
				final FeatureState myState = featuresState.get( i ).get( f );

				boolean present = false;

				for ( final FeatureState s : states )
					present |= ( s == myState );

				if ( present )
				{
					// found it
					currentFeature = f;

					if ( i != currentImage() )
						sliderImg.setValue( i );
					else
						updateFeature();

					return true;
				}
			}

			startFeature = numFeatures - 1;
		}

		return false;
	}

	public void nextFeature( final boolean autosave )
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

				if ( autosave )
					SwingUtilities.invokeLater( () -> save( tool.dir ) );
			}
		}
	}

	public void prevFeature( final boolean autosave )
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

				if ( autosave )
					SwingUtilities.invokeLater( () -> save( tool.dir ) );
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
		// fetches state given the current image and feature
		final FeatureState state = featureState();

		barMinus1.setBackground( state == FeatureState.NEGATIVE ? Color.magenta : Color.lightGray );
		barZero.setBackground( state == FeatureState.ZERO ? Color.magenta : Color.lightGray );
		barPlus1.setBackground( state == FeatureState.POSITIVE ? Color.magenta : Color.lightGray );

		updateGlobalProgress();

		testAndUpdateFeatureComplete();

		if ( progress != null )
			progress.update();
	}

	public void setAllFeatureStatesInvalid()
	{
		for ( int i = 0; i < numFeatures; ++i )
			featuresState.get( currentImage() ).set( i, FeatureState.INVALID );

		currentFeature = 0;
		nextImage();

		reDrawGlobalProgress();
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
	public synchronized boolean save( final String dir )
	{
		final long time = System.currentTimeMillis();

		final String fn = new File( dir, csv ).getAbsolutePath();

		IJ.log( "Saving '" + fn + "' ... " );

		final String[] row = new String[ 1 + featureList.size() ];

		try ( final FileWriter writer = new FileWriter( fn ))
		{
			final String[] header = new String[ 1 + featureList.size() ];
			header[ 0 ] = "image_id";

			for ( int i = 0; i < featureList.size(); ++i )
				header[ i + 1 ] = featureList.get( i ).name;

			// Write header
			writer.append( String.join( csvSplitBy, header) );
			writer.append( "\n" );

			// Write data rows
			for ( int i = 0; i < featuresState.size(); ++i )
			{
				final ArrayList<FeatureState> data = featuresState.get( i );

				row[ 0 ] = Integer.toString( i );

				for ( int j = 0; j < featureList.size(); ++j )
					row[ j + 1 ] = Integer.toString( data.get( j ).ordinal() - 1 ); // -1 so 0 is represented as -1 in the file

				writer.append( String.join( csvSplitBy, row ) );
				writer.append( "\n" );
			}
		}
		catch (IOException e)
		{
			IJ.log( "Failed to write CSV: " + e );
			e.printStackTrace();
			return false;
		}

		IJ.log( "Saved '" + fn + "'. [took " + (System.currentTimeMillis() - time ) + " ms]");

		try ( final FileWriter writerState = new FileWriter( new File( dir, state ).getAbsolutePath() ))
		{
			writerState.append( Integer.toString( currentImage() ) );
			writerState.append( "\n" );
			writerState.append( Integer.toString( currentFeature ) );
			writerState.append( "\n" );
		}
		catch (IOException e) {}

		return true;
	}

	@Override
	public boolean load( final String dir )
	{
		final File file = new File( dir, csv );

		IJ.log( "Loading '" + file.getAbsolutePath() + "' ..." );

		if ( !file.exists() )
		{
			IJ.log( "File '" + file.getAbsolutePath() + "' does not exist, cannot load." );
			return false;
		}

		String line;

		try ( final BufferedReader br = new BufferedReader(new FileReader(file)))
		{
			int lineIndex = 0;

			while ((line = br.readLine()) != null)
			{
				final String[] dataLine = line.split( csvSplitBy );

				if ( dataLine.length != 1 + featureList.size() )
				{
					IJ.log( "Cannot read CSV, number of rows (" + (dataLine.length - 1) + ") is not compatible with loaded JSON (" + featureList.size() + ")." );
					throw new RuntimeException( "Cannot read CSV, number of rows (" + (dataLine.length - 1) + ") is not compatible with loaded JSON (" + featureList.size() + ")." );
				}

				if ( lineIndex == 0 )
				{
					// header, ignore
				}
				else
				{
					final int imageIndex = Integer.parseInt( dataLine[ 0 ] );
					if ( imageIndex != lineIndex - 1 )
					{
						IJ.log( "Image index (" + imageIndex + ") does not match line index (" + (lineIndex - 1) + "). Stopping." );
						throw new RuntimeException( "Image index (" + imageIndex + ") does not match line index (" + (lineIndex - 1) + "). Stopping." );
					}

					final ArrayList<FeatureState> data = featuresState.get( imageIndex );

					for ( int j = 0; j < featureList.size(); ++j )
						data.set( j, FeatureState.values()[ Integer.parseInt( dataLine[ j + 1 ] ) + 1 ] ); // +1 because 0 is represented as -1 in the file
				}
		
				++lineIndex;
			}
		}
		catch (IOException e)
		{
			IJ.log( "Failed to load '" + file.getAbsolutePath() + "': " + e );
			e.printStackTrace();
			return false;
		}

		IJ.log( "Loaded '" + file.getAbsolutePath() + "'." );

		reDrawGlobalProgress();

		return true;
	}

	public void loadAndApplyState( final String dir )
	{
		try ( final BufferedReader br = new BufferedReader(new FileReader(new File( dir, state ))))
		{
			final String line1 = br.readLine();
			final String line2 = br.readLine();

			currentFeature = Integer.parseInt( line2 );
			sliderImg.setValue( Integer.parseInt( line1 ) );
		}
		catch (Exception e) {}
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
