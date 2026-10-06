package org.btsn.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;

import org.btsn.utils.XPathHelperCommon;

public class Scheduler {

	public ArrayList<Long> prioritiseToken(int queueAction, String servicePacket) throws IOException {
		long costKey = 0;

		ArrayList<Long> returnArgs = new ArrayList<Long>();

		TreeMap<String, String> headerMap = new TreeMap<String, String>();
		XPathHelperCommon xph = new XPathHelperCommon();
		TreeMap<String, String> attrMap = new TreeMap<String, String>();
		/*
		 * Determine if Least Remaining Deadline is to be used
		 */
		try {
			headerMap = xph.findMultipleXMLItems(servicePacket, "//header/*");
			attrMap = xph.findMultipleXMLItems(servicePacket, "//joinAttribute/*");
			boolean priorityOrder = Boolean.parseBoolean(headerMap.get("priortiseSID"));

			/*
			 * If the queue is full and a token is not available, then ditch the packet and do nothing. The advantage of
			 * this approach is that the queue can grow if the reactor has already received one argument in a join.
			 */
			int sid = Integer.parseInt(headerMap.get("sequenceId"));

			if (!(queueAction > 0)) {
				if (!ServiceThread.argValPriorityMap.containsKey(sid)) {
					costKey = -1;
					returnArgs.add(costKey);
					return returnArgs;
				}
			}
			/*
			 * sequenceID is used to prioritise the queue if LRD is selected - first check to see if token has expired
			 */

			long timeofArrival = System.currentTimeMillis();
			long notAfter = Long.parseLong(attrMap.get("notAfter"));

			attrMap = xph.findMultipleXMLItems(servicePacket, "//joinAttribute/*");
			if (timeofArrival > notAfter) {
				costKey = -1;
				returnArgs.add(costKey);
				return returnArgs;
			}
			/*
			 * The priority is now set at using the sequenceID so the code here is deprecated, and will be removed in
			 * time
			 */
			/*
			 * We are going to use priority based the sequenceID, ie a unique ordering mechanism bunches all joins
			 * together, Sequence IDs begin at 10000 and increment in terms of 100, i.e. 10000, 10100, 10200 etc and
			 * joins increment as 10000, 10001, 10002 would be three arguments requiring to be present to allow a
			 * service operation to be performed. This determines the packet's priority.
			 */
			//costKey = sid;
			//costKey = (sid * 1000L) + (System.currentTimeMillis() % 1000);
			costKey = (sid * 1000L) + (System.currentTimeMillis() % 1000);

			/* if (!priorityOrder) { */
			/*
			 * Use cost key for FIFO based on time of initiation. For testing we can use this for timing the local task
			 */
			/*
			 * costKey = timeofArrival;
			 * 
			 * } else {
			 * 
			 * costKey = sid;
			 * 
			 * }
			 */

			/*
			 * If returnArgs(2) == 0 then FIFO is used and timeofArrival is the costKey If returnArgs(2) <> 0 then
			 * priority based on notAfter is set, and costKey is notAfter, and returnArgs(2) = timeofArrival and
			 * returnArgs(3) is notAfter to assist
			 */
			returnArgs.add(costKey);
			returnArgs.add((long) sid);
			// returnArgs.add(System.currentTimeMillis()); // This is eventStartTime
			// returnArgs.add(notAfter);

		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		return returnArgs;

	}
}
