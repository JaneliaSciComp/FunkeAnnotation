package util;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.IntStream;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.janelia.saalfeldlab.n5.GsonUtils;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import fiji.tool.SliceListener;
import fiji.tool.SliceObserver;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.TextRoi;
import ij.measure.Calibration;
import ij.plugin.Animator;
import ij.plugin.PlugIn;
import ij.plugin.Zoom;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.util.Pair;

@Plugin(type = Command.class, menuPath = "Plugins>Funke lab>Annotator ...")
public class MLTool implements Command, PlugIn
{
	// Done: default Mask: 0.5, red
	// Done: on start ask for root directory
	// Done: zoom to 300%
	// Done: play button (with FPS) > turn into 4D image
	// Done: support images: 
	// directory a,b,m: increasing ID, no leading zeros
	// Done: save in the parent directory
	// Done: first iteration a single text file
	public static final String notes = "notes.txt";

	final ForkJoinPool myPool = new ForkJoinPool( Runtime.getRuntime().availableProcessors() );

	volatile ForkJoinTask<?> task = null;

	ByteProcessor ip1, ip2, mask;
	ImagePlus mainImp;
	HashMap< Integer, ColorProcessor > main;
	ImageStack stack;

	Color color = setColor( Color.orange );
	float r, g, b;

	SliceObserver sliceObserver;
	String dir;
	ByteProcessor[] imgsA, imgsB, imgsM;

	public abstract static class GUIStateBasic
	{
		final String labelDialog = "Annotation for image ";
		final String impDialog = "Image ";

		JDialog dialog;
		JSlider sliderImg, sliderMask;
		JButton text1, text2, text3, text4;
		JButton back, forward, save, quit;

		public abstract boolean save( final String dir );
		public abstract boolean load( final String dir );
	}

	public static class GUIStatePhase1 extends GUIStateBasic
	{
		JTextArea textfield;

		@Override
		public boolean load( final String dir )
		{
			final File f = new File( dir, notes );

			if ( !f.exists() )
				return false;

			try
			{
				final BufferedReader inputFile = new BufferedReader(new FileReader( f ));

				String concatenated = "";
				String l = null;

				while ( ( l = inputFile.readLine() ) != null )
					concatenated += l + "\n";

				concatenated = concatenated.trim();

				textfield.setText( concatenated );

				inputFile.close();
			}
			catch (Exception e)
			{
				IJ.log( "Couldn't load file: '" + f + "': " + e);
				e.printStackTrace();
				return false;
			}

			IJ.log( "Successfully LOADED file: '" + f + "'." );

			return true;
		}

		@Override
		public boolean save( final String dir )
		{
			final String fn = new File( dir, notes ).getAbsolutePath();
			try
			{
				final PrintWriter outputFile = new PrintWriter(new FileWriter( fn ));
				outputFile.print( textfield.getText().trim() );
				outputFile.close();
			}
			catch (IOException e)
			{
				IJ.log( "Couldn't save file: '" + fn + "': " + e);
				e.printStackTrace();
				return false;
			}

			IJ.log( "Successfully SAVED file: '" + fn + "'." );

			return true;
		}
	}

	public static class GUIStatePhase2 extends GUIStateBasic
	{
		// the state of the GUI when rating images
		public enum FeatureState { NEGATIVE, ZERO, POSITIVE, NOT_ASSIGNED, INVALID }

		List<Feature> featureList;
		int numImages, numFeatures;
		final ArrayList< ArrayList< FeatureState > > featuresState = new ArrayList<>();

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
		private static class Feature
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


	public void setup( final String dir )
	{
		this.dir = dir;

		final File dirA = new File( dir, "A" );
		final File dirB = new File( dir, "B" );
		final File dirM = new File( dir, "M" );

		final List<String> filesA = Arrays.asList( dirA.list( (d,n) -> n.toLowerCase().endsWith( ".png" ) ) );
		final List<String> filesB = Arrays.asList( dirB.list( (d,n) -> n.toLowerCase().endsWith( ".png" ) ) );
		final List<String> filesM = Arrays.asList( dirM.list( (d,n) -> n.toLowerCase().endsWith( ".png" ) ) );

		if ( filesA == null || filesA.size() == 0 )
		{
			IJ.log( "No PNG's found in " + dirA );
			throw new RuntimeException( "No PNG's found in " + dirA );
		}

		if ( filesB == null || filesB.size() == 0 )
		{
			IJ.log( "No PNG's found in " + dirB );
			throw new RuntimeException( "No PNG's found in " + dirB );
		}

		if ( filesM == null || filesM.size() == 0 )
		{
			IJ.log( "No PNG's found in " + dirM );
			throw new RuntimeException( "No PNG's found in " + dirM );
		}

		if ( filesA.size() != filesB.size() || filesA.size() != filesM.size() )
		{
			IJ.log( "Amount of files is not equal..." );
			throw new RuntimeException( "Amount of files is not equal..." );
		}

		IJ.log( "found: " + filesA.size() + " pngs in A, " + filesB.size() + " pngs in B, "+ filesM.size() + " pngs in M; loading all ...");

		final long time = System.currentTimeMillis();

		final int numFiles = filesA.size();

		imgsA = new ByteProcessor[ numFiles ];
		imgsB = new ByteProcessor[ numFiles ];
		imgsM = new ByteProcessor[ numFiles ];

		IntStream.range( 0, numFiles ).parallel().forEach( i ->
		{
			final ImagePlus impA = new ImagePlus( new File( dirA.getAbsolutePath(), i + ".png" ).getAbsolutePath() );
			final ImagePlus impB = new ImagePlus( new File( dirB.getAbsolutePath(), i + ".png" ).getAbsolutePath() );
			final ImagePlus mask = new ImagePlus( new File( dirM.getAbsolutePath(), i + ".png" ).getAbsolutePath() );

			imgsA[ i ] = new ByteProcessor( impA.getWidth() + 32, impA.getHeight() );
			imgsB[ i ] = new ByteProcessor( impA.getWidth() + 32, impA.getHeight() );
			imgsM[ i ] = new ByteProcessor( impA.getWidth() + 32, impA.getHeight() );

			final ByteProcessor ipTmpA = (ByteProcessor)impA.getProcessor();
			final ByteProcessor ipTmpB = (ByteProcessor)impB.getProcessor();
			final ByteProcessor ipTmpM = (ByteProcessor)mask.getProcessor();

			for ( int y = 0; y < impA.getHeight(); ++y )
				for ( int x = 0; x < impA.getWidth(); ++x )
				{
					final int i1 = y * impA.getWidth() + x;
					final int i2 = y * imgsA[ i ].getWidth() + x;

					imgsA[ i ].set( i2, ipTmpA.get( i1 ) );
					imgsB[ i ].set( i2, ipTmpB.get( i1 ) );
					imgsM[ i ].set( i2, ipTmpM.get( i1 ) );
				}
		});

		IJ.log( "Done, took " + ( System.currentTimeMillis() - time ) + " ms." );

		setImages( imgsA[ 0 ], imgsB[ 0 ], imgsM[ 0 ] );
	}

	public void setImages( final ByteProcessor ip1, final ByteProcessor ip2, final ByteProcessor mask )
	{
		this.ip1 = ip1;
		this.ip2 = ip2;
		this.mask = mask;
	}

	public synchronized void interpolateMainImage( final GUIStateBasic state )
	{
		if ( task != null )
		{
			task.cancel( true );
			task = null;
		}

		try
		{
			task = myPool.submit(() ->
				main.entrySet().parallelStream().parallel().forEach( entry ->
					interpolateMask(ip1, ip2, (float)entry.getKey() / 100f, mask, (float)state.sliderMask.getValue() / 100f, r,g,b , entry.getValue())
				));//.get();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

		mainImp.updateAndDraw();
	}

	public Color setColor( final Color color )
	{
		if ( color == null )
			return null;

		this.color = color;

		this.r = 1.0f - color.getRed() / 255f;
		this.g = 1.0f - color.getGreen() / 255f;
		this.b = 1.0f - color.getBlue() / 255f;

		return color;
	}

	public static void interpolateMask( final ByteProcessor input1, final ByteProcessor input2, final float amount, final ByteProcessor mask, final float amountMask, final float amountR, final float amountG, final float amountB, final ColorProcessor target )
	{
		final int numPixels = target.getWidth() * target.getHeight();
		final float invAmount = 1.0f - amount;

		for ( int i = 0; i < numPixels; ++i )
		{
			final float v = input1.get( i ) * invAmount + input2.get( i ) * amount;
			final float maskedV = (mask.getf( i ) / 255.0f) * amountMask;
			final float tmp = v * maskedV;

			target.set( i, ARGBType.rgba(v - amountR*tmp, v- amountG*tmp, v- amountB*tmp, 255.0f) );
		}
	}

	public void showDialog(
			final int maxFrame,
			final double defaultMagnification,
			final int defaultMask,
			final Color defaultColor,
			final boolean loadExisting,
			final String json )
	{
		setColor( defaultColor );

		final boolean phase1 = (json == null || json.trim().length() == 0);
		final GUIStateBasic state = ( phase1 ? new GUIStatePhase1() : new GUIStatePhase2() );

		// create dialog
		state.dialog = new JDialog( (JFrame)null, state.labelDialog + "0", false);
		state.dialog.setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;

		// GRID Y=0
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 1*2;
		state.text1 = new JButton( "Image 0" );
		state.text1.setBorderPainted( false );
		state.dialog.add( state.text1, c );

		c.gridx = 1*2;
		c.gridy = 0;
		c.gridwidth = 2*2;
		state.sliderImg = new JSlider( 0, imgsA.length - 1 );
		state.sliderImg.setValue( 0 );
		state.sliderImg.addChangeListener( e -> {
			setImages( imgsA[ state.sliderImg.getValue() ], imgsB[ state.sliderImg.getValue() ], imgsM[ state.sliderImg.getValue() ] );
			interpolateMainImage( state );
			state.dialog.setTitle( state.labelDialog + state.sliderImg.getValue() );
			mainImp.setTitle( state.impDialog + state.sliderImg.getValue() );
		});
		state.dialog.add( state.sliderImg, c );

		c.gridx = 3*2;
		c.gridy = 0;
		c.gridwidth = 1*2;
		state.text2 = new JButton( "Image " + ( imgsA.length - 1 ) );
		state.text2.setBorderPainted( false );
		state.dialog.add( state.text2, c );

		// GRID Y=1
		c.gridx = 0;
		c.gridy = 1;
		c.gridwidth = 1*2;
		state.text3 = new JButton( "Mask 0.0" );
		state.text3.setBorderPainted( false );
		state.text3.setForeground( color );
		state.text3.addActionListener( e ->
		{
			setColor( JColorChooser.showDialog( state.dialog, "Choose a color", color ) );
			state.text3.setForeground( color );
			state.text4.setForeground( color );
			interpolateMainImage( state );
		} );
		state.dialog.add( state.text3, c );

		c.gridx = 1*2;
		c.gridy = 1;
		c.gridwidth = 2*2;
		state.sliderMask = new JSlider( 0, 100 );
		state.sliderMask.setValue( defaultMask );
		state.sliderMask.addChangeListener( e -> interpolateMainImage( state ) );
		state.dialog.add( state.sliderMask, c );

		c.gridx = 3*2;
		c.gridy = 1;
		c.gridwidth = 1*2;
		state.text4 = new JButton( "Mask 1.0" );
		state.text4.setBorderPainted( false );
		state.text4.setForeground( color );
		state.text4.addActionListener( e ->
		{
			setColor( JColorChooser.showDialog(state. dialog, "Choose a color", color ) );
			state.text3.setForeground( color );
			state.text4.setForeground( color );
			interpolateMainImage( state );
		} );
		state.dialog.add( state.text4, c );

		// GRID Y=2
		c.gridx = 0;
		c.gridy = 2;
		c.gridwidth = 1*2;
		state.back = new JButton( "Prev. Img" );
		state.back.addActionListener( e -> state.sliderImg.setValue( state.sliderImg.getValue() - 1) ) ;
		state.dialog.add( state.back, c );

		c.gridx = 1*2;
		c.gridy = 2;
		c.gridwidth = 1*2;
		state.forward = new JButton( "Next Img" );
		state.forward.addActionListener( e -> state.sliderImg.setValue( state.sliderImg.getValue() + 1) ) ;
		state.dialog.add( state.forward, c );

		c.gridx = 2*2;
		c.gridy = 2;
		c.gridwidth = 1*2;
		state.save = new JButton( "Save" );
		state.save.addActionListener( e -> state.save( dir ) );
		state.dialog.add( state.save, c );

		c.gridx = 3*2;
		c.gridy = 2;
		c.gridwidth = 1*2;
		state.quit = new JButton( "Quit" );
		state.quit.addActionListener( e ->
		{
			final int choice = JOptionPane.showConfirmDialog( state.dialog,
					"Do you want to save before closing?",
					"Confirmation",
					JOptionPane.YES_NO_CANCEL_OPTION );
			
			if ( choice == JOptionPane.CANCEL_OPTION )
				return;
			else if ( choice == JOptionPane.YES_OPTION )
				if ( !state.save( dir ) )
					return;

			sliceObserver.unregister();
			mainImp.close();
			state.dialog.dispose();
		});
		state.dialog.add( state.quit, c );

		// GRID Y=3
		if ( phase1 )
		{
			final GUIStatePhase1 state1 = (GUIStatePhase1)state;

			c.fill = GridBagConstraints.HORIZONTAL;
			c.gridx = 0;
			c.gridy = 3;
			c.gridwidth = 4*2;
			c.gridheight = 2;
			c.ipady = 200;
			c.ipadx = 300;
			state1.textfield = new JTextArea();
			state.dialog.add( new JScrollPane(state1.textfield), c );
	
			// try loading an existing notes file
			if ( loadExisting )
				state1.load( dir );
		}
		else
		{
			final GUIStatePhase2 state2 = (GUIStatePhase2)state;

			final JPopupMenu popupMenu3 = new JPopupMenu();
			final JMenuItem item3 = new JMenuItem( "Next image without annotations" );
			final JMenuItem item4 = new JMenuItem( "Next image marked as invalid" );
			item3.addActionListener( e -> {}); // TODO
			item4.addActionListener( e -> {}); // TODO
			popupMenu3.add( item3 );
			popupMenu3.add( item4 );
			state2.forward.setComponentPopupMenu( popupMenu3 );

			final JPopupMenu popupMenu4 = new JPopupMenu();
			final JMenuItem item5 = new JMenuItem( "Previous image without annotations" );
			final JMenuItem item6 = new JMenuItem( "Previous image marked as invalid" );
			item5.addActionListener( e -> {}); // TODO
			item6.addActionListener( e -> {}); // TODO
			popupMenu4.add( item5 );
			popupMenu4.add( item6 );
			state2.back.setComponentPopupMenu( popupMenu4 );

			c.gridx = 0;
			c.gridy = 3;
			c.gridwidth = 4*2;
			c.gridheight = 1;
			final JLabel featureLabel = new JLabel( "Feature 1/" + state2.featureList.size() + ": " + state2.featureList.get( 0 ).name, SwingConstants.CENTER );
			featureLabel.setBackground( new Color( 255, 128, 128 ) );
			featureLabel.setOpaque(true);
			featureLabel.setBorder( BorderFactory.createLineBorder(Color.BLACK, 1) );
			state2.dialog.add( featureLabel, c );

			// GRID Y=4
			c.gridx = 0;
			c.gridy = 4;
			c.gridwidth = 4*2;
			c.gridheight = 1;
			final JLabel featureDescMinusOne = new JLabel( "-: " + state2.featureList.get( 0 ).minusOne, SwingConstants.CENTER );
			featureDescMinusOne.setFont( new Font( featureDescMinusOne.getFont().getName(), Font.PLAIN, featureDescMinusOne.getFont().getSize() - 2) );
			state2.dialog.add( featureDescMinusOne, c );

			// GRID Y=5
			c.gridx = 0;
			c.gridy = 5;
			c.gridwidth = 4*2;
			c.gridheight = 1;
			final JLabel featureDescZero = new JLabel( "0: " + state2.featureList.get( 0 ).zero, SwingConstants.CENTER );
			featureDescZero.setFont( new Font( featureDescZero.getFont().getName(), Font.PLAIN, featureDescZero.getFont().getSize() - 2) );
			state2.dialog.add( featureDescZero, c );

			// GRID Y=6
			c.gridx = 0;
			c.gridy = 6;
			c.gridwidth = 4*2;
			c.gridheight = 1;
			final JLabel featureDescPlusOne = new JLabel( "+: " + state2.featureList.get( 0 ).plusOne, SwingConstants.CENTER );
			featureDescPlusOne.setFont( new Font( featureDescPlusOne.getFont().getName(), Font.PLAIN, featureDescPlusOne.getFont().getSize() - 2) );
			state2.dialog.add( featureDescPlusOne, c );

			// GRID Y=7
			c.gridx = 0;
			c.gridy = 7;
			c.gridwidth = 1;
			final JButton buttonMinus1 = new JButton( " - " );
			buttonMinus1.setFont( buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			buttonMinus1.setForeground( Color.magenta );
			state2.dialog.add( buttonMinus1, c );

			c.gridx = 1;
			c.gridy = 7;
			c.gridwidth = 1;
			final JButton buttonZero = new JButton( " 0 " );;
			buttonZero.setFont( buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			buttonZero.setForeground( Color.magenta );
			state2.dialog.add( buttonZero, c );

			c.gridx = 2;
			c.gridy = 7;
			c.gridwidth = 1;
			final JButton buttonPlus1 = new JButton( " + " );;
			buttonPlus1.setFont( buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			buttonPlus1.setForeground( Color.magenta );
			state2.dialog.add( buttonPlus1, c );

			c.gridx = 3;
			c.gridy = 7;
			c.gridwidth = 1;
			final JLabel placeholder1 = new JLabel( "                  " );;
			state2.dialog.add( placeholder1, c );

			c.gridx = 4;
			c.gridy = 7;
			c.gridwidth = 1;
			final JLabel placeholder2 = new JLabel( "                  " );;
			state2.dialog.add( placeholder2, c );

			c.gridx = 5;
			c.gridy = 7;
			c.gridwidth = 1;
			final JButton buttonPrevFeature = new JButton( " -F " );;
			buttonPrevFeature.setFont( buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			buttonPrevFeature.setForeground( Color.GREEN.darker().darker().darker() );
			final JPopupMenu popupMenu1 = new JPopupMenu();
			final JMenuItem item1 = new JMenuItem( "Previous un-annotated feature" );
			item1.addActionListener( e -> {});
			popupMenu1.add( item1 );
			buttonPrevFeature.setComponentPopupMenu( popupMenu1 );
			state2.dialog.add( buttonPrevFeature, c );

			c.gridx = 6;
			c.gridy = 7;
			c.gridwidth = 1;
			final JButton buttonNextFeature = new JButton( " +F " );;
			buttonNextFeature.setFont( buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			buttonNextFeature.setForeground( Color.GREEN.darker().darker().darker() );
			final JPopupMenu popupMenu2 = new JPopupMenu();
			final JMenuItem item2 = new JMenuItem( "Next un-annotated feature" );
			item2.addActionListener( e -> {});
			popupMenu2.add( item2 );
			buttonNextFeature.setComponentPopupMenu( popupMenu2 );
			state2.dialog.add( buttonNextFeature, c );

			c.gridx = 7;
			c.gridy = 7;
			c.gridwidth = 1;
			final JButton buttonNextImage = new JButton( " X " );;
			buttonNextImage.setFont( buttonMinus1.getFont().deriveFont( Font.BOLD ) );
			buttonNextImage.setForeground( Color.RED );
			state2.dialog.add( buttonNextImage, c );

			// GRID Y=8
			c.gridx = 0;
			c.gridy = 8;
			c.gridwidth = 1;
			final JLabel barMinus1 = new JLabel("     ");
			barMinus1.setFont( new Font( "Arial", Font.PLAIN, 6 ) );
			barMinus1.setBackground( Color.magenta );
			barMinus1.setOpaque( true );
			state2.dialog.add( barMinus1, c );

			c.gridx = 1;
			c.gridy = 8;
			c.gridwidth = 1;
			final JLabel barZero = new JLabel("     ");
			barZero.setFont( new Font( "Arial", Font.PLAIN, 6 ) );
			barZero.setBackground( Color.magenta );
			barZero.setOpaque( false );
			state2.dialog.add( barZero, c );

			c.gridx = 0;
			c.gridy = 8;
			c.gridwidth = 1;
			final JLabel barPlus1 = new JLabel("     ");
			barPlus1.setFont( new Font( "Arial", Font.PLAIN, 6 ) );
			barPlus1.setBackground( Color.magenta );
			barPlus1.setOpaque( false );
			state2.dialog.add( barPlus1, c );
		}

		// show dialog
		state.dialog.pack();
		state.dialog.setVisible(true);

		System.out.println( state.back.getSize() );
		System.out.println( state.forward.getSize() );


		// setup ImageJ window
		this.main = new HashMap<>();
		this.stack = new ImageStack();

		// create empty imagestack
		for ( int f = 0; f <= maxFrame; ++f )
		{
			final ColorProcessor cp = new ColorProcessor( imgsA[ 0 ].getWidth(), imgsA[ 0 ].getHeight());
			this.main.put( f, cp );
			this.stack.addSlice( cp );
		}

		// create image window
		this.mainImp = new ImagePlus( state.impDialog + "0", this.stack );

		// setup calibration for animation
		final Calibration cal = this.mainImp.getCalibration();
		cal.fps = 25;
		cal.loop = true;

		// fill image stack with interpolated image data
		interpolateMainImage( state );

		// setup overlays, add listener
		final Overlay ov = new Overlay();
		this.mainImp.setOverlay( ov );

		final Font font = new Font(" SansSerif", Font.PLAIN, 26);

		final TextRoi textROIA = new TextRoi(135, 12, "A", font );
		textROIA.setStrokeColor( new Color( 255, 255, 255 ) );
		ov.add( textROIA );

		final TextRoi textROIB = new TextRoi(136, 85, "B", font );
		textROIB.setStrokeColor( new Color( 255, 0, 0 ) );
		ov.add( textROIB );

		SliceListener sliceListener = l ->
		{
			final int frame = this.mainImp.getZ() - 1;

			final float c1 = (float)frame / (float)maxFrame;
			final float c2 = 1.0f - c1;

			textROIA.setStrokeColor( new Color( c2, c2, c2 ) );
			textROIB.setStrokeColor( new Color( c1, c1, c1 ) );
		};
		sliceObserver = new SliceObserver(this.mainImp, sliceListener );

		// show main window
		this.mainImp.show();

		new Thread(() ->
		{
			// default maginfication (is slow)
			Zoom.set( this.mainImp, defaultMagnification );

			// start animation
			new Animator().run( "start" );
		}).start();
	}

	public static String defaultDirectory = "";
	public static String defaultJSON = "";
	public static boolean defaultLoadExisting = true;

	@Override
	public void run(String arg)
	{
		GenericDialogPlus gd = new GenericDialogPlus( "Select base directory" );

		gd.addDirectoryField("Directory", defaultDirectory, 80 );
		gd.addFileField( "Annotation JSON", defaultJSON, 80 );
		gd.addCheckbox( "Load existing notes (only relevant if no JSON is specified)", defaultLoadExisting);

		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		setup( defaultDirectory = gd.getNextString() );
		SwingUtilities.invokeLater(() ->
			this.showDialog( 100, 3.0, 50, Color.orange, defaultLoadExisting = gd.getNextBoolean(), defaultJSON = gd.getNextString() ) );
	}

	@Override
	public void run() { run( null ); }

	public static void main( String[] args )
	{
		//try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception e) { }

		new ImageJ();
		defaultDirectory = "/Users/preibischs/Documents/Janelia/Projects/Funke/phase1/";
		defaultJSON = "/Users/preibischs/Documents/Janelia/Projects/Funke/phase2_example.json";

		new MLTool().run( null );

		//final MLTool tool = new MLTool();
		//tool.setup( "/Users/preibischs/Documents/Janelia/Projects/Funke/phase1/" );
		//SwingUtilities.invokeLater(() -> tool.showDialog( 100, 3.0, 50, Color.orange ) );

		/*
		ImagePlus imp1 = new ImagePlus( "/Users/preibischs/Documents/Janelia/Projects/Funke/image001.png");
		//ImagePlus imp2 = new ImagePlus( "/Users/preibischs/Documents/Janelia/Projects/Funke/image002.png");
		//ImagePlus mask = new ImagePlus( "/Users/preibischs/Documents/Janelia/Projects/Funke/image003.png");
		//setImages( (ByteProcessor)imp1.getProcessor(), (ByteProcessor)imp2.getProcessor(), (ByteProcessor)mask.getProcessor() );

		Overlay ov = new Overlay();
		imp1.setOverlay( ov );
		
		final Font font = new Font(" SansSerif", Font.PLAIN, 26);
		Color color = new Color( 1.00f, 1.00f, 1.00f );

		final TextRoi textROI = new TextRoi(50, 60, "A", font );
		textROI.setStrokeColor( color );
		ov.add( textROI );

		imp1.show();

		for ( int i = 128; i < 256; ++i )
		{
			SimpleMultiThreading.threadWait( 10 );
			color = new Color(i, i, i);
			textROI.setStrokeColor( color );
			imp1.updateAndDraw();
		}
		//final OvalRoi or = new OvalRoi(20, 20, 30, 30 );
		//or.setStrokeColor( Color.red );
		//ov.add(or);
		imp1.updateAndDraw();
		*/

	}
}
