package de.hsrm.blaubot.core.statemachine.states;

import de.hsrm.blaubot.core.ConnectionStateMachineConfig;
import de.hsrm.blaubot.core.IBlaubotConnection;
import de.hsrm.blaubot.core.State;
import de.hsrm.blaubot.core.statemachine.BlaubotAdapterHelper;
import de.hsrm.blaubot.core.statemachine.StateMachineSession;
import de.hsrm.blaubot.core.statemachine.events.AbstractBlaubotDeviceDiscoveryEvent;
import de.hsrm.blaubot.core.statemachine.events.AbstractTimeoutStateMachineEvent;
import de.hsrm.blaubot.message.admin.ACKPronouncePrinceAdminMessage;
import de.hsrm.blaubot.message.admin.AbstractAdminMessage;
import de.hsrm.blaubot.message.admin.BowDownToNewKingAdminMessage;
import de.hsrm.blaubot.message.admin.CensusMessage;
import de.hsrm.blaubot.message.admin.PronouncePrinceAdminMessage;
import de.hsrm.blaubot.util.Log;

/**
 * 
 * @author Henning Gross <mail.to@henning-gross.de>
 *
 */
public class PeasantState implements IBlaubotState, IBlaubotSubordinatedState {
	private static final int MAX_RETRIES_TO_CONNECT_TO_PRINCE_OR_KING = 4;
	private static final String LOG_TAG = "PeasantState";
	private StateMachineSession session;
	private IBlaubotConnection kingConnection;
	private final ConnectionAccomplishmentType connectionAccomplishmentType;
	
	public enum ConnectionAccomplishmentType {
		/**
		 * We connected voluntarily to the current king (Free -> Peasant)
		 */
		VOLUNTARILY, 
		/**
		 * We connected to the king after a bow down.
		 */
		BOWED_DOWN,
		/**
		 * We connected to the Prince after the King died.
		 */
		FOLLOWED_THE_HEIR_TO_THE_THRONE,
		
		/**
		 * We were degraded from {@link PrinceState} to {@link PeasantState}
		 */
		DEGRADATION
	}

	/**
	 * @param kingConnection the connection to our king
	 */
	public PeasantState(IBlaubotConnection kingConnection, ConnectionAccomplishmentType connectionAccomplishmentType) {
		this.connectionAccomplishmentType = connectionAccomplishmentType;
		this.kingConnection = kingConnection;
		if(kingConnection == null)
			throw new NullPointerException();
	}
	
	/**
	 * @return VOLUNTARILY if we connected to the king from Free state, BOWED_DOWN if we connected to the king due to a {@link BowDownToNewKingAdminMessage}
	 */
	public ConnectionAccomplishmentType getConnectionAccomplishmentType() {
		return connectionAccomplishmentType;
	}
	
	@Override
	public IBlaubotState onConnectionEstablished(IBlaubotConnection connection) {
		if(connection != kingConnection) {
			if(session.getConnectionManager().getAllConnections().contains(connection)) {
				throw new IllegalStateException("Got a connection as peasant. The only allowed connection in peasant state is the king's connection.");
			} else {
				// everything ok - we probably got an event after we transitioned from KingState to PeasantState
			}
		}
		return this;
	}

	@Override
	public IBlaubotState onConnectionClosed(IBlaubotConnection connection) {
		// -- we are peasant and lost a connection
		/*
		 * We previously asserted at this point, that we have NO connections anymore and threw
		 * an exception if there was at least one connection remaining. But now we moved on to
		 * a single threaded event queue for the ConnectionStateMachine which changes the game.
		 * 
		 * It is possible that more than one closed events occur here. This can happen if we 
		 * just transitioned from king to peasant state because we merged our kingdom and bowed 
		 * down to another king.
		 * In this process the former king (we) close all connections to our peasants right
		 * before we transition to the peasant state. The connectionClosed events are now pushed
		 * to the ConnectionStateMachine's event queue and will therefore occur in the new state
		 * (this state) and can be safely ignored.
		 */

		if (connection == kingConnection) {
			if (Log.logDebugMessages()) {
				Log.d(LOG_TAG, "We lost the king-connection in PeasantState. We assume our king to be lost and start connecting to the prince.");
			}
			// we lost our king - look out for the prince
			CensusMessage lastCensusMessage = session.getLastCensusMessage();
			if(lastCensusMessage == null) {
				if (Log.logWarningMessages()) {
					Log.w(LOG_TAG, "Could not determine prince device (never got a census message.");
				}
				if (Log.logDebugMessages()) {
					Log.d(LOG_TAG, "Connection to prince failed. Changing state to FreeState");
				}
				// TODO: maybe retry?
				return new FreeState();
			}
			// Get the adapter's config
			ConnectionStateMachineConfig conf = connection.getRemoteDevice().getAdapter().getConnectionStateMachineConfig();
			final int CROWNING_PREPARATION_TIME = conf.getCrowningPreparationTimeout();
			
			// find the prince
			for(String uniqueId : lastCensusMessage.getDeviceStates().keySet()) {
				State state = lastCensusMessage.getDeviceStates().get(uniqueId);
				if(state.equals(State.Prince)) {
					// -- we found a prince
					// TODO: maybe add a retry mechanism
					if (Log.logDebugMessages()) {
						Log.d(LOG_TAG, "I know the prince device is " +uniqueId + ". I will give the king some time to prepare it's crowning and connect after that ...");
					}
					try {
						Thread.sleep(CROWNING_PREPARATION_TIME);
					} catch (InterruptedException e) {
						if (Log.logWarningMessages()) {
							Log.w(LOG_TAG, "Crowning got interrupted ...");
						}
						return this;
					}
					
					IBlaubotConnection conn = session.getConnectionManager().connectToBlaubotDevice(uniqueId, MAX_RETRIES_TO_CONNECT_TO_PRINCE_OR_KING);
					boolean result = conn != null;
					if(result) {
						if (Log.logDebugMessages()) {
							Log.d(LOG_TAG, "Connection to prince successful. Remaining in Peasant state.");
						}
						return new PeasantState(conn, ConnectionAccomplishmentType.FOLLOWED_THE_HEIR_TO_THE_THRONE);
					}
					if (Log.logWarningMessages()) {
						Log.w(LOG_TAG, "Connection to prince device failed ... changing to FreeState");
					}
					// connection failed, go to free state
					break;
				}
			}
			if (Log.logDebugMessages()) {
				Log.d(LOG_TAG, "Could not determine prince device (not present in last census). Changing to FreeState. Last CensusMessage was: " + session.getLastCensusMessage());
			}
			return new FreeState();
		} 
		
		return this; // ignore if not king connection
	}

	@Override
	public IBlaubotState onDeviceDiscoveryEvent(AbstractBlaubotDeviceDiscoveryEvent discoveryEvent) {
		return this;
	}

	@Override
	public void handleState(StateMachineSession session) {
		this.session = session;
		BlaubotAdapterHelper.stopAcceptors(session.getConnectionStateMachine().getConnectionAcceptors());
		BlaubotAdapterHelper.startBeacons(session.getBeaconService());
		// we deactivate discovery - so other devices can get our state but we are not actively scanning
		BlaubotAdapterHelper.setDiscoveryActivated(session.getBeaconService(), false);
		
//		// check if we are the prince
		CensusMessage lastCensusMessage = session.getLastCensusMessage();
		if(lastCensusMessage != null) {
			// simulate adminMessage
			onAdminMessage(lastCensusMessage);
		}
	}


	@Override
	public IBlaubotState onAdminMessage(AbstractAdminMessage adminMessage) {
		if(adminMessage instanceof PronouncePrinceAdminMessage) {
			PronouncePrinceAdminMessage ppam = (PronouncePrinceAdminMessage) adminMessage;
			if (Log.logDebugMessages()) {
				Log.d(LOG_TAG, "We got a PronouncePrinceAdminMessage. The new prince is " + ppam.getUniqueDeviceId());
			}
			String newPrinceUniqueId = ppam.getUniqueDeviceId();
			boolean weArePrince = session.isDeviceOneOfOurs(newPrinceUniqueId);
			if(weArePrince) {
				if (Log.logDebugMessages()) {
					Log.d(LOG_TAG, "We are the new prince, sending ACK and changing state.");
				}
				// we are the new prince! -> send ACK and change state
				ACKPronouncePrinceAdminMessage ackMsg = new ACKPronouncePrinceAdminMessage(newPrinceUniqueId);
				session.getAdminBroadcastChannel().post(ackMsg.toBlaubotMessage());
				return new PrinceState(kingConnection);
			} else {
				if (Log.logDebugMessages()) {
					Log.d(LOG_TAG, "We are not prince. Remaining in PeasantState.");
				}
			}
		} else if (adminMessage instanceof BowDownToNewKingAdminMessage) {
			kingConnection.disconnect();
			BowDownToNewKingAdminMessage bowDownToNewKingAdminMessage = (BowDownToNewKingAdminMessage) adminMessage;
			if (Log.logDebugMessages()) {
				Log.d(LOG_TAG, "We got a BowDownToNewKingAdminMessage. The new king is " + bowDownToNewKingAdminMessage.getNewKingsUniqueDeviceId());
				Log.d(LOG_TAG, "Trying to connect to new king ...");
			}
			
			IBlaubotConnection conn = session.getConnectionManager().connectToBlaubotDevice(bowDownToNewKingAdminMessage.getNewKingsUniqueDeviceId(), MAX_RETRIES_TO_CONNECT_TO_PRINCE_OR_KING);
			boolean result = conn != null;
			if(result) {
				if (Log.logDebugMessages()) {
					Log.d(LOG_TAG, "Connection to new king successful. Remaining in Peasant state.");
				}
				return new PeasantState(conn, ConnectionAccomplishmentType.BOWED_DOWN);
			} else {
				if (Log.logWarningMessages()) {
					Log.w(LOG_TAG, "Connection to new king failed! Oh my, now we are an outlaw :-(. Changing to FreeState to find a new king.");
				}
				return new FreeState();
			}
		}
		return this;
	}
	
	@Override
	public String getKingUniqueId() {
		return kingConnection.getRemoteDevice().getUniqueDeviceID();
	}
	
	@Override
	public String toString() {
		return "PeasantState";
	}

	@Override
	public IBlaubotState onTimeoutEvent(AbstractTimeoutStateMachineEvent timeoutEvent) {
		return this;
	}

	@Override
	public IBlaubotConnection getKingConnection() {
		return kingConnection;
	}
}
