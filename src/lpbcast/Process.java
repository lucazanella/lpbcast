/**
 * 
 */
package lpbcast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import lpbcast.ActiveRetrieveRequest.Destination;
import repast.simphony.context.Context;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.schedule.ScheduledMethod;
import repast.simphony.random.RandomHelper;
import repast.simphony.util.ContextUtils;
import repast.simphony.util.collections.IndexedIterable;

/**
 * Represents a Process.
 * 
 * @author zanel
 * @author danie
 * @author coffee
 */
public class Process {
	
	/**
	 * The identifier of the process.
	 */
	public int processId;
	/**
	 * The subset of processes known by the current process. 
	 * Every process identifier is associated to its frequency.
	 */
	public HashMap<Integer, Integer> view;
	/**
	 * The buffer storing the incoming messages.
	 */
	public ConcurrentLinkedQueue<Message> receivedMessages;
	/**
	 * The buffer storing the event notifications received since the
	 * last outgoing gossip message.
	 */
	public HashSet<Event> events;
	/**
	 * The buffer storing the identifiers of the event notifications 
	 * the process has already delivered.
	 */
	public LinkedList<EventId> eventIds;
	/**
	 * The buffer storing the subscriptions. 
	 * Every process identifier is associated to its frequency.
	 */
	public HashMap<Integer, Integer> subs;
	/**
	 * The buffer storing the unsubscriptions.
	 * Every process identifier is associated to the tick in which the 
	 * unsubscription was added to the buffer.
	 */
	public HashMap<Integer, Double> unSubs;
	/**
	 * The buffer storing the event notifications that have been lost
	 * and have not been already requested.
	 */
	public HashSet<MissingEvent> retrieve;
	/**
	 * The buffer storing the old event notifications. It is used to satisfy
	 * retransmission requests.
	 * Every event notification is associated to the tick in which the
	 * event was added to the buffer.
	 */
	public HashMap<Event, Double> archivedEvents;
	/**
	 * The buffer storing the event notifications that have been lost
	 * and have been requested.
	 */
	public HashSet<ActiveRetrieveRequest> activeRetrieveRequest;
	/**
	 * The boolean denoting the unsubscription of the process.
	 */
	public boolean isUnsubscribed;
	/**
	 * The boolean denoting the request of the node to unsubscribe
	 * from the set of processes.
	 */
	public boolean unsubscriptionRequested; 
	
	public static final int EVENTS_MAX_SIZE = 5; // Just for debugging purposes
	public static final int UNSUBS_MAX_SIZE = 5; // Just for debugging purposes
	public static final int EVENTIDS_MAX_SIZE = 5;
	public static final int VIEW_MAX_SIZE = 5; // Just for debugging purposes
	public static final int SUBS_MAX_SIZE = 5; // Just for debugging purposes
	public static final int ARCHIVED_MAX_SIZE = 10; // Just for debugging purposes
	public static final int UNSUBS_VALIDITY = 100; // elements in the unSubs buffer are expire after this amount of tick has passed
	public static final int LONG_AGO = 100; // Just for debugging purposes
	public static final double K = 0.5; // Just for debugging purposes
	public static final int MESSAGE_MAX_DELAY = 1; // a message takes at most this amount of ticks to reach destination
	public static final boolean SYNC = true; // if set to false, message could have delays
	public static final int F = 3; // Just for debugging purposes
	public static final int RECOVERY_TIMEOUT = 20; // Retransmission timeout to different destinations
	public static final int K_RECOVERY = 20; // Enough tick passed eventId is eligible for recovery
	public static final boolean AGE_BASED_MESSAGE_PURGING = true; // Enable optimization that removes messages from event buffer based on their dissemination in the system
	public static final boolean FREQUENCY_BASED_MEMBERSHIP_PURGING = true; // Enable optimization that removes processIds form view and subs based on their dissemination in the system
	
	/**
	 * Instantiates a new process.
	 * 
	 * @param processId the identifier of the process
	 * @param view the subset of process known by this process
	 */
	public Process(int processId, HashMap<Integer, Integer> view) {
		this.processId = processId;
		this.view = view;
		this.receivedMessages = new ConcurrentLinkedQueue<>();
		this.events = new HashSet<>();
		this.eventIds = new LinkedList<>();
		this.subs = new HashMap<>();
		this.unSubs = new HashMap<>();
		this.retrieve = new HashSet<>();
		this.archivedEvents = new HashMap<>();
		this.activeRetrieveRequest = new HashSet<>();
		this.isUnsubscribed = false;
		this.unsubscriptionRequested = false;
	}
	
	/**
	 * Gets the current tick of the simulation.
	 * 
	 * @return the current tick
	 */
	public double getCurrentTick() {
		return RunEnvironment.getInstance().getCurrentSchedule().getTickCount();
	}
	
	/**
	 * Gets the reference of the process from its identifier.
	 * 
	 * @param processId the id of the process to be retrieved
	 * @return the process with the given id if it exists, null otherwise
	 */
	public Process getProcessById(int processId) {
		// retrieves the context of the current process
		Process target = null;
		Context<Process> context = ContextUtils.getContext(this);
		IndexedIterable<Process> collection =  context.getObjects(Process.class);
		Iterator<Process> iterator = collection.iterator();
		
		while(iterator.hasNext() & target == null) {
			Process process = iterator.next();
			if(process.processId == processId) {
				target = process;
			}
		}
		
		return target;
	}
	
	/**
	 * Inserts a message in the queue of incoming messages.
	 * 
	 * @param message the message to be sent
	 */
	public void receive(Message message) {
		double nextTick = getCurrentTick() + 1;
		if(SYNC) {
			// The message will be processes at the next tick
			message.tick = nextTick;
		} else {
			//The message is received at the next tick + a random delay
			message.tick = getCurrentTick() + RandomHelper.nextIntFromTo((int)nextTick, MESSAGE_MAX_DELAY);
		}

		receivedMessages.add(message);
	}
	
	/**
	 * Method that runs on each Repast tick and performs the basic logic for each node.
	 * 
	 * Each tick, a process follows the following logic: - If we are not unsubscribed,
	 * get the first message in the incoming queue which can be processed, meaning
	 * that the tick associated to that message is at least as big as the current tick
	 * (the message's tick is used to simulate network delays). Depending on the type
	 * of message, call the appropriate handler.
	 */
	@ScheduledMethod(start=1 , interval=1)
	public void step() {
		// check whether process should gossip or do nothing 
		if(!isUnsubscribed) {
			//extract from the receivedMessages queue the messages which arrive at the current tick
			Iterator<Message> it = this.receivedMessages.iterator();
			while(it.hasNext()) {
				Message message = it.next();
				if(message.tick <= this.getCurrentTick()) {
					switch(message.type) {
						case GOSSIP:
							this.gossipHandler((Gossip)message);
							break;
						case RETRIEVE_REQUEST:
							this.retrieveRequestHandler((RetrieveRequest)message);
							break;
						case RETRIEVE_REPLY:
							this.retrieveReplyHandler((RetrieveReply)message);
							break;
					}
					it.remove();
				}
			}
			
			//Check missing events
			this.retrieveMissingMessages();
			
			//Gossip
			this.gossip();
		}
	}
	
	/**
	 * Handles a gossip message. 
	 * 
	 * @param gossipMessage the gossip message 
	 */
	public void gossipHandler(Gossip gossipMessage) {
		
		// beginning of method updateUnSubs()
		
		// remove unsubs from current view and current subs
		// merge unsubs received with current unsubs vector
		for (Integer unsub : gossipMessage.unsubs) {
			view.remove(unsub);
			subs.remove(unsub);
			unSubs.putIfAbsent(unsub, getCurrentTick());
		}
		
		//trim unsubs buffer
		trimUnSubs();
		
		// end of method updateUnSubs()
		
		// beginning of method updateViewsAndSubs()
		
		for (Integer sub : gossipMessage.subs) {
			if(sub != processId) {
				view.putIfAbsent(sub, 0); // insert new element with frequency 0 if not already in the view
				view.put(sub, view.get(sub) + 1); // increment frequency of item
				
				subs.putIfAbsent(sub, 0); // insert new element with frequency 0 in subs if not already present
				subs.put(sub, subs.get(sub) + 1); // increment frequency of item
			}
		}
		
		//trim view buffer (by adding removed element to subs)
		this.trimView();  
		
		//trim subs buffer 
		this.trimSubs();
		
		// end of method updateViewsAndSubs()
		
		// beginning of method updateEvents()
		for(Event gossipEvent : gossipMessage.events) {
			this.processEvent(gossipEvent);
		}
		
		trimEvents();
		// end of method updateEvents()
		
		// begin of method updateEventIdse
		for(EventId eventId : gossipMessage.eventIds) {
			if(!eventIds.contains(eventId)) {
				// the event with this id is missing
				MissingEvent missingEvent = new MissingEvent(eventId, getCurrentTick(), gossipMessage.sender);
				
				boolean duplicateFound = false;
				for(MissingEvent me : retrieve) {
					if(me.eventId.equals(missingEvent.eventId)) {
						duplicateFound = true;
					}
				}
				
				if(!duplicateFound) {
					retrieve.add(missingEvent);
				}
			}
		}
		trimEventIds();
		// end of method updateEventIds 
	}
	
	/**
	 * Handles the request from a process to retrieve a missing event notifications.
	 * 
	 * @param retrieveRequestMessage the request to retrieve a missing
	 * event notification
	 */
	public void retrieveRequestHandler(RetrieveRequest retrieveRequestMessage) {
		EventId id = retrieveRequestMessage.eventId;
		// 1 -> Check if the event with that id is inside events
		for(Event ev : this.events) {
			if(ev.eventId.equals(id)) {
				RetrieveReply replyMessage = new RetrieveReply(this.processId, ev.clone());
				this.getProcessById(retrieveRequestMessage.sender).receive(replyMessage);
			}
		}
		// 2 -> Check if the event with that id is inside archivedEvents
		for(Map.Entry<Event, Double> entry : this.archivedEvents.entrySet()) {
			if(entry.getKey().eventId.equals(id)) {
				RetrieveReply replyMessage = new RetrieveReply(this.processId, entry.getKey().clone());
				this.getProcessById(retrieveRequestMessage.sender).receive(replyMessage);
			}
		}
	}
	
	/**
	 * Handles the reply from a process containing the missing event notification
	 * that the current process has requested for.
	 * 
	 * @param retrieveReplyMessage the reply containing the missing event 
	 * notification
	 */
	public void retrieveReplyHandler(RetrieveReply retrieveReplyMessage) {
		Iterator<ActiveRetrieveRequest> it = this.activeRetrieveRequest.iterator();
		while(it.hasNext()) {
			ActiveRetrieveRequest ar = it.next();
			if(retrieveReplyMessage.event.eventId.equals(ar.eventId)) {
				// Remove the element in activeRequest
				it.remove();
				// Process event received
				this.processEvent(retrieveReplyMessage.event);
				// Trim event buffer
				trimEvents();
			}
		}
	}
	
	/**
	 * Keeps fixed the size of the unsubscription buffer.
	 */
	public void trimUnSubs() {
		if(unSubs.size() > UNSUBS_MAX_SIZE) {
			// first trim is done based on expiration date of unsubs
			Iterator<Map.Entry<Integer, Double>> it = unSubs.entrySet().iterator();
			while(it.hasNext()) {
				Map.Entry<Integer, Double> pair = it.next();
				if(getCurrentTick() >= (pair.getValue() + UNSUBS_VALIDITY)) {
					it.remove(); // avoids a ConcurrentModificationException
				}
			}	
		}
		trimEvents();
		while(unSubs.size() > UNSUBS_MAX_SIZE) {
			// second trim is done by sampling random element
			// get a random key from the buffer HashMap
			Object[] bufferKeys = unSubs.keySet().toArray();
			assert bufferKeys.length > 0;
			int key = (Integer) bufferKeys[RandomHelper.nextIntFromTo(0, bufferKeys.length - 1)];
			unSubs.remove(key);
		}
	}
	
	/**
	 * Keeps fixed the size of the view buffer.
	 */
	public void trimView() {
		while(view.size() > VIEW_MAX_SIZE) {
			int target = selectProcess(view);
			int frequency = view.remove(target);
			subs.put(target, frequency);
		}
	}
	
	/**
	 * Keeps fixed the size of the subscription buffer.
	 */
	public void trimSubs() {
		while(subs.size() > SUBS_MAX_SIZE) {
			int target = selectProcess(subs);
			subs.remove(target);
		}
	}
	
	/**
	 * Selects randomly an element from a buffer based on the average frequency
	 * of the elements contained in the buffer.
	 * 
	 * @param buffer the buffer from which an element is selected
	 * @return the selected element
	 */
	public int selectProcess(HashMap<Integer, Integer> buffer) {	
		Integer target = null;
		if(Process.FREQUENCY_BASED_MEMBERSHIP_PURGING) {
			// optimization enable, use frequencies to decide which element must be removed
			boolean found = false;		
			Double averageFrequency = buffer.values().stream().mapToInt(i -> i).average().orElse(0.0);
			
			while(!found) {
				// get a random key from the buffer HashMap
				Object[] bufferKeys = buffer.keySet().toArray();
				assert bufferKeys.length > 0;
				target = (Integer) bufferKeys[RandomHelper.nextIntFromTo(0, bufferKeys.length - 1)];
				Integer currentFrequency = buffer.get(target);
				
				if(currentFrequency > K * averageFrequency) {
					found = true;
				} else {
					// the old value of frequency is replaced with the new one
					buffer.put(target, currentFrequency + 1);
				}
			}
		} else {
			// non-optimized version, remove random element from set
			Object[] bufferKeys = buffer.keySet().toArray();
			assert bufferKeys.length > 0;
			target = (Integer) bufferKeys[RandomHelper.nextIntFromTo(0, bufferKeys.length - 1)];
		}
		
		assert target != null;
		return target;
	}
	
	/**
	 * Keeps fixed the size of the events buffer.
	 */
	public void trimEvents() {
		if(Process.AGE_BASED_MESSAGE_PURGING) {
			// remove elements from events buffer that were received a long time ago wrt
			// to more recent messages from the same broadcast source
			if(events.size() > EVENTS_MAX_SIZE) {
				HashSet<Event> eventsToRemove = new HashSet<>();
				Iterator<Event> it = events.iterator();
			    while(it.hasNext()) {
			      Event currentEvent = it.next();
			      List<Event> filtered = events.stream()
			          .filter(e -> e.eventId.origin == currentEvent.eventId.origin && (currentEvent.age - e.age) > LONG_AGO)
			          .collect(Collectors.toList());
			      eventsToRemove.addAll(filtered);
			    }		    
			    events.removeAll(eventsToRemove);
			}

			// remove elements from events buffer with the largest age
			while(events.size() > EVENTS_MAX_SIZE) {
				Event oldestEvent = null;
				
				// find the oldest event
				for(Event e : events) {
					// first iteration
					if (oldestEvent == null) {
						oldestEvent = e;
					} else {
						if(e.age > oldestEvent.age) {
							oldestEvent = e;
						}
					}
				}
				
				events.remove(oldestEvent);
				// if the map previously contained a mapping for the key, the old value is replaced
				archivedEvents.put(oldestEvent, getCurrentTick());
			}
		} else {
			// remove elements from event buffer randomly
			while(events.size() > EVENTS_MAX_SIZE) {
				int targetIndex = RandomHelper.nextIntFromTo(0, events.size() - 1);
				Event targetEvent = (Event) events.toArray()[targetIndex];
				events.remove(targetEvent);
				archivedEvents.put(targetEvent, this.getCurrentTick());
			}
		}
		
		// trim archived events buffer
		this.trimArchivedEvents();
	}
	
	/**
	 * Keeps fixed the size of the archived events buffer.
	 */
	public void trimArchivedEvents() {
		while(this.archivedEvents.size() > ARCHIVED_MAX_SIZE) {
			Event minKey = null;
			Double minValue = Double.MAX_VALUE; 
			//Find oldest event
			for(Map.Entry<Event, Double> entry : this.archivedEvents.entrySet()) {
				if(entry.getValue() < minValue) {
					minValue = entry.getValue();
					minKey = entry.getKey();
				}
			}
			// If it exists, remove it
			this.archivedEvents.remove(minKey);
		}
	}
	
	/**
	 * Inserts an event notification and its identifier in the respective buffers,
	 * if the event has not already been delivered. Then, updates the age of the event.
	 * 
	 * @param newEvent the newly received event
	 */
	public void processEvent(Event newEvent) {
		if(!eventIds.contains(newEvent.eventId)) {
			events.add(newEvent);
			lpbDelivery(newEvent);
			eventIds.add(newEvent.eventId);
		}
		
		for(Event event : events) {
			if(newEvent.eventId.equals(event.eventId) & (event.age < newEvent.age)) {
				event.age = newEvent.age;
			}
		}
	}
	
	/**
	 * Keeps fixed the size of the events' identifiers buffer.
	 */
	public void trimEventIds() {
		while(this.eventIds.size() > EVENTIDS_MAX_SIZE) {
			eventIds.remove();
		}
	}
	
	/**
	 * Starts retrieving missing event notifications.
	 */
	public void retrieveMissingMessages() {
		//Update active request, checking if timeout occurs
		this.updateActiveRetrieveRequests();
		//Check if new request need to be performed
		Iterator<MissingEvent> it = this.retrieve.iterator();
		while(it.hasNext()){
			MissingEvent me = it.next();
			if(this.getCurrentTick() - me.tick > K_RECOVERY) {
				if(!this.eventIds.contains(me.eventId)) {
					// Check whether an active request for that eventId already exists
					boolean alreadyActive = false;
					for(ActiveRetrieveRequest ar : this.activeRetrieveRequest) {
						if(ar.eventId.equals(me.eventId)) {
							alreadyActive = true;
							break;
						}
					}
					if(!alreadyActive) {
						// Create end send a retrieve message to the sender
						RetrieveRequest retrieveMessage = new RetrieveRequest(me.sender, me.eventId);
						this.getProcessById(me.sender).receive(retrieveMessage);
						// Create and add a new ActiveRequest
						ActiveRetrieveRequest ar = new ActiveRetrieveRequest(me.eventId, this.getCurrentTick(), Destination.SENDER);
						this.activeRetrieveRequest.add(ar);
					}
				}
				// In any case, remove the message from the retrieve queue (either received or request sent)
				it.remove();
			}
		}
	}
	
	/**
	 * Gossips a fixed number F of processes randomly selected from the view.
	 */
	public void gossip() {
		HashSet<Integer> gossipSubs;
		HashSet<Integer> gossipUnSubs;
		HashSet<Event> gossipEvents;
		HashSet<EventId> gossipEventIds;
		
		for(Event e : events) {
			e.age += 1;
		}
		
		gossipSubs = new HashSet<Integer>(subs.keySet());
		// if the process does not want to unsubscribe than adds itself into the subscription buffer
		if(!unsubscriptionRequested) {
			gossipSubs.add(processId);
		} else {
			unSubs.put(processId, getCurrentTick());
		}
		
		gossipUnSubs = new HashSet<Integer>(unSubs.keySet());
		
		gossipEvents = new HashSet<Event>();
		for(Event e : events) {
			// avoid multiple processes share the same reference
			gossipEvents.add(e.clone());
		}
		
		gossipEventIds = new HashSet<EventId>();
		for(EventId eId : eventIds) {
			// avoid multiple processes share the same reference
			gossipEventIds.add(eId.clone());
		}
		
		// create a gossip message
		Gossip gossip = new Gossip(processId, gossipEvents, gossipSubs, gossipUnSubs, gossipEventIds);
		
		// get a random key from the buffer HashMap
		Object[] bufferKeys = view.keySet().toArray();
		HashSet<Integer> gossipTargets = new HashSet<>();
		
		// if view size is smaller than fanout, number of target processes is less then fanout
		int numTarget = view.size() >= F ? F : view.size();
		while(gossipTargets.size() < numTarget) {
			assert bufferKeys.length > 0;
			int target = (Integer) bufferKeys[RandomHelper.nextIntFromTo(0, bufferKeys.length - 1)];
			// adds target only if it is not already contained
			gossipTargets.add(target);
		}
		
		for(Integer gossipTarget : gossipTargets) {
			Process currentTarget = getProcessById(gossipTarget);
			currentTarget.receive(gossip);
		}
		
		// clear buffer events and store them in archivedEvents
		Iterator<Event> it = events.iterator();
		while(it.hasNext()) {
			Event e = it.next();
			it.remove();	// removes event from events
			archivedEvents.put(e, this.getCurrentTick());
		}
		
		// process wants to unsubscribe, clear the buffers and set boolean flag
		if(unsubscriptionRequested) {
			view.clear();
			subs.clear();
			unSubs.clear();
			eventIds.clear();
			archivedEvents.clear();
			retrieve.clear();
			activeRetrieveRequest.clear();
			
			//clear message queue
			receivedMessages.clear();
			
			isUnsubscribed = true;
			unsubscriptionRequested = false;
		}
	}
	
	/**
	 * Updates the issued requests to retrieve missing event notifications.
	 */
	public void updateActiveRetrieveRequests() {
		Iterator<ActiveRetrieveRequest> it = activeRetrieveRequest.iterator();
		while(it.hasNext()) {
			ActiveRetrieveRequest ar = it.next();
			if(this.getCurrentTick() - ar.tick >= RECOVERY_TIMEOUT) {
				switch(ar.destination) {
					case SENDER:
						RetrieveRequest randMessage = new RetrieveRequest(this.processId, ar.eventId);
						// get a random processId from the view
						Object[] viewKeys = view.keySet().toArray();
						assert viewKeys.length > 0;
						int target = (Integer) viewKeys[RandomHelper.nextIntFromTo(0, viewKeys.length - 1)];
						// send message to a random process in the view
						getProcessById(target).receive(randMessage);
						// update the active request
						ar.tick = this.getCurrentTick();
						ar.destination = Destination.RANDOM;
						break;
					case RANDOM:
						RetrieveRequest origMessage = new RetrieveRequest(this.processId, ar.eventId);
						// send message to the originator
						getProcessById(ar.eventId.origin).receive(origMessage);
						// update the active request
						ar.tick = this.getCurrentTick();
						ar.destination = Destination.ORIGINATOR;
						break;
					case ORIGINATOR:
						// the retrieve message is lost
						it.remove();
						break;
					default:
						assert false;
					}
			}
		}
	}
	
	/**
	 * Simulates the probabilistic delivery of an event notification.
	 * 
	 * @param event the event notification to be delivered
	 */
	public void lpbDelivery(Event event) {
		System.out.println("Deliver event " + event.eventId.id);
	}
	
	/**
	 * Simulates the probabilistic broadcast of an event notification.
	 */
	public void lpbCast() {
		//Generate a new Event
		Event newEvent = new Event(new EventId(UUID.randomUUID(), this.processId), 0);
		// Add event to events buffer and the relative id inside eventIds
		this.events.add(newEvent);
		this.eventIds.add(newEvent.eventId);
		
	}
	
	/**
	 * Unsubscribes the process from the set of processes.
	 */
	public void unsubscribe() {
		unsubscriptionRequested = true;
	}
	
	/**
	 * Subscribes the process to a set of process through an entry point.
	 * 
	 * @param targetId the identifier of the process used as entry point 
	 * in order to join the network
	 */
	public void subscribe(int targetId) {
		assert isUnsubscribed;
		// when a process wants to join the network clear message queue containg old message
		receivedMessages.clear();
		// insert targetId inside view
		view.put(targetId, 0);
		// change subscription status
		isUnsubscribed = false;
	}
}
