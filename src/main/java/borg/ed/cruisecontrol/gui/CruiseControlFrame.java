package borg.ed.cruisecontrol.gui;

import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

import boofcv.struct.image.GrayF32;
import borg.ed.cruisecontrol.DebugImageListener;
import borg.ed.universe.journal.JournalUpdateListener;
import borg.ed.universe.journal.Status;
import borg.ed.universe.journal.StatusUpdateListener;
import borg.ed.universe.journal.events.AbstractJournalEvent;
import borg.ed.universe.journal.events.ReceiveTextEvent;
import borg.ed.universe.journal.events.SendTextEvent;

public class CruiseControlFrame extends JFrame implements WindowListener, KeyListener, DebugImageListener, JournalUpdateListener, StatusUpdateListener {

	private static final long serialVersionUID = 882373007986678048L;

	private static final String SPAN_INACTIVE = "<span style=\"color: grey;\">";
	private static final String SPAN_ACTIVE = "<span style=\"color: blue;\">";
	private static final String SPAN_CAUTION = "<span style=\"color: red;\">";
	private static final String SPAN_CLOSE = "</span>";

	private final DebugPanel debugPanel;
	private final JLabel lblJournal;
	private final JLabel lblStatus;
	private final JLabel lblChat;

	private final LinkedList<String> lastJournalMessages = new LinkedList<>();
	private final LinkedList<String> lastChatMessages = new LinkedList<>();

	public CruiseControlFrame() {
		super("EDCC");

		this.debugPanel = new DebugPanel();
		this.lblJournal = new JLabel();
		this.lblJournal.setVerticalAlignment(SwingConstants.TOP);
		this.lblStatus = new JLabel();
		this.lblStatus.setVerticalAlignment(SwingConstants.TOP);
		this.lblChat = new JLabel();
		this.lblChat.setVerticalAlignment(SwingConstants.TOP);

		this.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		this.addWindowListener(this);
		this.setLayout(new BorderLayout());
		this.add(this.debugPanel, BorderLayout.CENTER);
		JPanel southPanel = new JPanel(new BorderLayout());
		southPanel.add(new JScrollPane(this.lblChat), BorderLayout.CENTER);
		southPanel.add(new JScrollPane(this.lblJournal), BorderLayout.WEST);
		this.add(this.lblStatus, BorderLayout.WEST);
		this.add(southPanel, BorderLayout.SOUTH);
		this.setSize(1900, 1050);
		this.setLocation(-1820, 1440 - 1070);
		//this.setLocation(2000, -200);
		this.addKeyListener(this);
	}

	@Override
	public void windowOpened(WindowEvent e) {
		// Do nothing
	}

	@Override
	public void windowClosing(WindowEvent e) {
		// Do nothing
	}

	@Override
	public void windowClosed(WindowEvent e) {
		System.exit(0);
	}

	@Override
	public void windowIconified(WindowEvent e) {
		// Do nothing
	}

	@Override
	public void windowDeiconified(WindowEvent e) {
		// Do nothing
	}

	@Override
	public void windowActivated(WindowEvent e) {
		// Do nothing
	}

	@Override
	public void windowDeactivated(WindowEvent e) {
		// Do nothing
	}

	@Override
	public void keyTyped(KeyEvent e) {
		// Do nothing
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
			System.exit(-1);
		} else if (e.getKeyCode() == KeyEvent.VK_S) {
			this.debugPanel.writeScreenCapture();
		} else {
			System.out.println("keyCode=" + e.getKeyCode());
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {
		// Do nothing
	}

	@Override
	public void onNewDebugImage(BufferedImage debugImage, GrayF32 orangeHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage) {
		this.debugPanel.updateScreenCapture(debugImage, orangeHudImage, blueWhiteHudImage, redHudImage, brightImage);
	}

	@Override
	public void onNewJournalEntry(AbstractJournalEvent event) {
		if (event instanceof ReceiveTextEvent || event instanceof SendTextEvent) {
			synchronized (this.lastChatMessages) {
				this.lastChatMessages.addFirst(event.toString());
				while (this.lastChatMessages.size() > 10) {
					this.lastChatMessages.removeLast();
				}
				StringBuilder text = new StringBuilder();
				for (String msg : this.lastChatMessages) {
					text.append(msg + "\n");
				}
				this.lblChat.setText("<html>" + text.toString().trim().replace("\n", "<br>") + "</html>");
			}
		} else {
			synchronized (this.lastJournalMessages) {
				this.lastJournalMessages.addFirst(event.toString());
				while (this.lastJournalMessages.size() > 10) {
					this.lastJournalMessages.removeLast();
				}
				StringBuilder text = new StringBuilder();
				for (String msg : this.lastJournalMessages) {
					text.append(msg + "\n");
				}
				this.lblJournal.setText("<html>" + text.toString().trim().replace("\n", "<br>") + "</html>");
			}
		}
	}

	@Override
	public void onNewStatus(Status status) {
		if (status == null) {
			this.lblStatus.setText("");
		} else {
			StringBuilder text = new StringBuilder("<html>");

			text.append(status.getTimestamp().format(DateTimeFormatter.ISO_INSTANT)).append("<br>");

			text.append("<br>").append(status.isDocked() ? SPAN_ACTIVE : SPAN_INACTIVE).append("Docked").append(SPAN_CLOSE);
			text.append("<br>").append(status.isLanded() ? SPAN_ACTIVE : SPAN_INACTIVE).append("Landed").append(SPAN_CLOSE);
			text.append("<br>").append(status.isLandingGearDown() ? SPAN_ACTIVE : SPAN_INACTIVE).append("Landing gear down").append(SPAN_CLOSE);
			text.append("<br>").append(status.isShieldsUp() ? SPAN_ACTIVE : SPAN_CAUTION).append("Shields up").append(SPAN_CLOSE);
			text.append("<br>").append(status.isInSupercruise() ? SPAN_ACTIVE : SPAN_INACTIVE).append("In supercruise").append(SPAN_CLOSE);
			text.append("<br>").append(status.isFlightAssistOff() ? SPAN_CAUTION : SPAN_INACTIVE).append("FA off").append(SPAN_CLOSE);
			text.append("<br>").append(status.isHardpointsDeployed() ? SPAN_ACTIVE : SPAN_INACTIVE).append("Hardpoints deployed").append(SPAN_CLOSE);
			text.append("<br>").append(status.isInWing() ? SPAN_ACTIVE : SPAN_INACTIVE).append("In wing").append(SPAN_CLOSE);
			text.append("<br>").append(status.isLightsOn() ? SPAN_ACTIVE : SPAN_INACTIVE).append("Lights on").append(SPAN_CLOSE);
			text.append("<br>").append(status.isCargoScoopDeployed() ? SPAN_ACTIVE : SPAN_INACTIVE).append("Cargo scoop deployed").append(SPAN_CLOSE);
			text.append("<br>").append(status.isSilentRunning() ? SPAN_CAUTION : SPAN_INACTIVE).append("Silent running").append(SPAN_CLOSE);
			text.append("<br>").append(status.isScoopingFuel() ? SPAN_ACTIVE : SPAN_INACTIVE).append("Scooping fuel").append(SPAN_CLOSE);
			text.append("<br>").append(status.isSrvHandbrake() ? SPAN_ACTIVE : SPAN_INACTIVE).append("SRV handbrake").append(SPAN_CLOSE);
			text.append("<br>").append(status.isSrvTurret() ? SPAN_ACTIVE : SPAN_INACTIVE).append("SRV turret").append(SPAN_CLOSE);
			text.append("<br>").append(status.isSrvUnderShip() ? SPAN_ACTIVE : SPAN_INACTIVE).append("SRV under ship").append(SPAN_CLOSE);
			text.append("<br>").append(status.isSrvDriveAssistOn() ? SPAN_ACTIVE : SPAN_INACTIVE).append("SRV drive assist").append(SPAN_CLOSE);
			text.append("<br>").append(status.isFsdMassLocked() ? SPAN_ACTIVE : SPAN_INACTIVE).append("FSD mass locked").append(SPAN_CLOSE);
			text.append("<br>").append(status.isFsdCharging() ? SPAN_ACTIVE : SPAN_INACTIVE).append("FSD charging").append(SPAN_CLOSE);
			text.append("<br>").append(status.isFsdCooldown() ? SPAN_ACTIVE : SPAN_INACTIVE).append("FSD cooldown").append(SPAN_CLOSE);
			text.append("<br>").append(status.isLowFuel() ? SPAN_CAUTION : SPAN_INACTIVE).append("Low fuel").append(SPAN_CLOSE);
			text.append("<br>").append(status.isOverHeating() ? SPAN_CAUTION : SPAN_INACTIVE).append("Overheating").append(SPAN_CLOSE);
			text.append("<br>").append(status.hasLatLon() ? SPAN_ACTIVE : SPAN_INACTIVE).append("Has lat/lon").append(SPAN_CLOSE);
			text.append("<br>").append(status.isInDanger() ? SPAN_CAUTION : SPAN_INACTIVE).append("In danger").append(SPAN_CLOSE);
			text.append("<br>").append(status.isBeingInterdicted() ? SPAN_CAUTION : SPAN_INACTIVE).append("Being interdicted").append(SPAN_CLOSE);
			text.append("<br>").append(status.isInMothership() ? SPAN_ACTIVE : SPAN_INACTIVE).append("In mothership").append(SPAN_CLOSE);
			text.append("<br>").append(status.isInFighter() ? SPAN_ACTIVE : SPAN_INACTIVE).append("In fighter").append(SPAN_CLOSE);
			text.append("<br>").append(status.isInSrv() ? SPAN_ACTIVE : SPAN_INACTIVE).append("In SRV").append(SPAN_CLOSE);

			if ((status.getLatitude() != null && status.getLongitude() != null) || status.getHeading() != null || status.getAltitude() != null) {
				text.append("<br>");
			}
			if (status.getLatitude() != null && status.getLongitude() != null) {
				text.append("<br>Lat/Lon: " + status.getLatitude() + " / " + status.getLongitude());
			}
			if (status.getHeading() != null) {
				text.append("<br>Heading: " + status.getHeading() + "°");
			}
			if (status.getAltitude() != null) {
				text.append("<br>Altitude: " + status.getAltitude().divide(new BigDecimal(1000), 2, BigDecimal.ROUND_HALF_UP) + " km");
			}

			text.append("</html>");

			this.lblStatus.setText(text.toString());
		}
	}

}
