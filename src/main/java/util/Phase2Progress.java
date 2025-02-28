package util;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import util.GUIStatePhase2.FeatureState;

public class Phase2Progress extends JDialog
{
	private static final long serialVersionUID = 4432651136038026730L;

	final static String labelDialog = "Progress for image ";

	final GUIStatePhase2 state;
	final ArrayList< JLabel > labels;

	public Phase2Progress( final GUIStatePhase2 state )
	{
		super( (JFrame)null, labelDialog + "0", false );

		this.state = state;
		this.state.register( this );
		this.labels = new ArrayList<>( state.numFeatures );

		setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;

		c.gridwidth = 3;

		for ( int y = 0; y < state.numFeatures; ++y )
		{
			c.gridx = 0;
			c.gridy = y;
			c.gridwidth = 1;

			final FeatureState myState = state.featuresState.get( state.currentImage() ).get( y );
			final Pair<Color, String> props = getLabelProperties( myState );

			final JLabel label1 = new JLabel( props.getB() );
			label1.setFont(  new Font("Monospaced", Font.BOLD, label1.getFont().getSize()));//label1.getFont().deriveFont( Font.BOLD));
			label1.setOpaque( true );
			label1.setBackground( props.getA() );
			this.add( label1, c );
			labels.add( label1 );

			final int featureIndex = y;
			final JLabel label = label1;

			label1.addMouseListener( new MouseListener()
			{
				
				@Override
				public void mouseReleased(MouseEvent e) {  }
				
				@Override
				public void mousePressed(MouseEvent e)
				{
					state.currentFeature = featureIndex;
					state.updateFeature();

					final Color old = label.getBackground();

					new Thread( () ->
					{
						for ( int i = 0; i < 3; ++i )
						{
							label.setBackground( Color.white );
							label.repaint();
							try { Thread.sleep( 50 ); } catch (InterruptedException e1) {}
							label.setBackground( old );
							label.repaint();
							try { Thread.sleep( 50 ); } catch (InterruptedException e1) {}
						}
					} ).start();
				}
				
				@Override
				public void mouseExited(MouseEvent e) {}
				
				@Override
				public void mouseEntered(MouseEvent e) {}
				
				@Override
				public void mouseClicked(MouseEvent e) {}
			});
			c.gridx = 2;
			c.gridwidth = 2;
			final JLabel label2 = new JLabel( "  " + state.featureList.get( y ).name );
			this.add( label2, c );
		}

		// show dialog
		this.pack();
		this.setVisible(true);
	}

	public void update()
	{
		setTitle( labelDialog + state.currentImage() );

		for ( int y = 0; y < state.numFeatures; ++y )
		{
			final FeatureState myState = state.featuresState.get( state.currentImage() ).get( y );
			final Pair<Color, String> props = getLabelProperties( myState );

			labels.get( y ).setBackground( props.getA() );
			labels.get( y ).setText( props.getB() );
		}
	}

	public static Pair<Color, String> getLabelProperties( final FeatureState myState )
	{
		final Color col;
		final String text;

		if ( myState == FeatureState.INVALID )
		{
			col = GUIStatePhase2.invalidFeatureSet;
			text = "  X  ";
		}
		else if ( myState == FeatureState.NOT_ASSIGNED )
		{
			col = GUIStatePhase2.incompleteFeatureSet;
			text = "     ";
		}
		else if ( myState == FeatureState.NEGATIVE )
		{
			col = GUIStatePhase2.completeFeatureSet;
			text = "  -  ";
		}
		else if ( myState == FeatureState.ZERO )
		{
			col = GUIStatePhase2.completeFeatureSet;
			text = "  0  ";
		}
		else //if ( myState == FeatureState.POSITIVE )
		{
			col = GUIStatePhase2.completeFeatureSet;
			text = "  +  ";
		}

		return new ValuePair<>(col, text);
	}
}
