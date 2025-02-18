package util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import javax.swing.JTextArea;

import ij.IJ;

public class GUIStatePhase1 extends GUIState
{
	public static final String notes = "notes.txt";
	public JTextArea textfield;

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
