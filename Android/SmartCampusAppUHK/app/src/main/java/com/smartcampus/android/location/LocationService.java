/*
Copyright (c) 2014, Aalborg University
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the <organization> nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.smartcampus.android.location;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.smartcampus.android.location.wifi.AlgorithmNNSS;
import com.smartcampus.android.location.wifi.EstimateResult;
import com.smartcampus.android.location.wifi.IPositioningAlgorithm;
import com.smartcampus.android.location.wifi.WifiPosEngine;
import com.smartcampus.android.wifi.WifiMeasurementBuilder;
import com.smartcampus.indoormodel.AbsoluteLocation;
import com.smartcampus.indoormodel.Building;
import com.smartcampus.tracking.TrackedPosition;
import com.smartcampus.webclient.BatchUpdater;
import com.smartcampus.webclient.IWebClient;
import com.smartcampus.webclient.JsonWebClient;
import com.smartcampus.webclient.snifferbackend.IInfrastructurePositioningService;
import com.smartcampus.webclient.snifferbackend.InfrastructurePositionEstimate;
import com.smartcampus.webclient.snifferbackend.JsonInfrastructurePositioningClient;
import com.smartcampus.wifi.MacInfo;
import com.smartcampus.wifi.WifiMeasurement;

/**
 * This is the central point of communication with the SmartCampusAAU library
 * (i.e., it is a facade of the underlying positioning functionality). 
 * Usage of the class is decribed on the SmartCampusAAU homepage ( http://smartcampus.cs.aau.dk/downloads.html )
 * @author rhansen
 *
 */
public class LocationService extends Service implements SensorEventListener {

	private class CorrectBuildingWifiReceiver extends BroadcastReceiver {
		private int numScansToCreateMeasurement;
		private int scanNo;
		private WifiMeasurement lastMeasurement;
		private boolean isDone;
		
		public CorrectBuildingWifiReceiver(int useNumScans) {
			this.numScansToCreateMeasurement = useNumScans;
			lastMeasurement = null; 
			isDone = false;
			scanNo = 0;
		}
		
		public WifiMeasurement getMeasurement()
		{
			return this.lastMeasurement;
		}

		public boolean isDone() { return isDone; }
		
		@Override
		public void onReceive(Context c, Intent intent) {
			
			if (lastMeasurement == null)
			{
				lastMeasurement = new WifiMeasurement();
			}
			
			List<ScanResult> results = mWifiMan.getScanResults();
			for (ScanResult result : results) {
				//build measurement
				lastMeasurement.addValue(result.BSSID, result.level, new MacInfo(result));
			}

			scanNo++;
			if (scanNo >= numScansToCreateMeasurement)
			{
				isDone = true;				
			}
			else 
			{
				mWifiMan.startScan();
			}
		}
	}	
	
	/**
	 * Used to fetch a graph from the server asynchronously
	 * @author rhansen
	 */
	private class DownloadGraphTask extends AsyncTask<Integer, Void, Building>
	{
		//private IWebClient mWebClient = new DomWebClient(); //this client downloads via http and constructs a DOM which is parsed
		private IWebClient mWebClient = new JsonWebClient(); //this client downloads via http and
		private int mGraphToDownloadId;
		private String downloadMsg = "Ok";
		//Download a building (radio map) in the background
		@Override		
		protected Building doInBackground(Integer ... arg0) {
			mGraphToDownloadId = arg0[0];
			Iterable<Building> availBuildings = LocationService.cAvailableShallowBuildings;
			if (availBuildings != null)
			{
				for (Building b : availBuildings)
					if (b.getBuildingID() == mGraphToDownloadId)
					{
						LocationService.CurrentBuilding = b;
						break;
					}
			}			
			try
			{
				return mWebClient.downloadRadioMap(mGraphToDownloadId);
			}
			catch (Exception ex) //IOException will most likely be the cause
			{
				downloadMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
				return null;
			}
		}

		@Override
		protected void onPostExecute(Building arg)
		{
			if (arg == null)
			{
				setWifiStatus(STATUS_CONNECTION_PROBLEM);
			}
			else 
			{
				//NB: Only set the graph model - refactor to avoid confusion
				if (LocationService.CurrentBuilding != null)
					LocationService.CurrentBuilding.setGraphModel(arg.getGraphModel());
				else
					LocationService.CurrentBuilding = arg; //redundant to store both building and graph
				setWifiStatus(STATUS_RADIOMAP_DOWNLOADED);						
			}
			LocationService.this.notifyWifiStatusChanged(downloadMsg);			
		}	
	}

	/**
	 * Used to enable indoor positioning.
	 * If a radio map has not been downloaded recently, then a new radio map is fetched. 
	 * @author rhansen
	 */
	private class EnableIndoorPositioningTask extends AsyncTask<Void, Void, Void>
	{
		private String downloadMsg = "Ok";
		
		private boolean availableShallowBuildingRequireUpdate() {
			boolean requiresUpdate = false;
			
			//we haven't downloaded before
			if (cLastAvailableBuildingDownload == null)
			{
				requiresUpdate = true;
			}
			else
			{
				//Has it been more than 180 minutes since last download?
				final int downloadInterval = 180;
				
				/* joda version
				org.joda.time.DateTime now = new DateTime();
				org.joda.time.Period period = new Period(cLastAvailableBuildingDownload, now);
				if (period.getMinutes() >= downloadInterval)
				{
					requiresUpdate = true;				
				}
				else
				{
					requiresUpdate = false;
				}
				*/
				//java util version				
				Date now = new Date();
				long minutes = minutesBetween(cLastAvailableBuildingDownload, now);
				if (minutes >= downloadInterval)
					requiresUpdate = true;
				else
					requiresUpdate = false;
				
			}

			//we have downloaded within the last 5 minutes
			return requiresUpdate;
		}

		private long minutesBetween(Date start, Date end)
		{
			long diffInSeconds = (end.getTime() - start.getTime()) / 1000;
		    return diffInSeconds / 60;
		}
		
		private boolean buildingRequiresUpdate(int buildingID) {
						
			//we haven't downloaded before
			//joda version:
			//DateTime lastUpdated = cLastGraphDownloads.get(buildingID);
			//util date version:
			Date lastUpdated = cLastGraphDownloads.get(buildingID);
			
			if (lastUpdated == null)
				return true;
			
			//we haven't downloaded in the last 5 minutes
			final int downloadInterval = 5;
			
			//joda version
			/*
			org.joda.time.DateTime now = new DateTime();
			org.joda.time.Period period = new Period(lastUpdated, now);
			int minutes = period.getMinutes();
			if (minutes >= downloadInterval)
			{
				return true;				
			}
			*/
			Date now = new Date();
			long minutes = minutesBetween(lastUpdated, now);
			if (minutes >= downloadInterval)
				return true;
			
			//we have downloaded within the last 5 minutes
			return false;
		}

		//Download a building (radio map) in the background
		@Override		
		protected Void doInBackground(Void... arg0) {
			cIsEnablingWifi = true;
			
			sendBroadcast(new Intent(PROGRESS_STATUS_DETERMINING_BUILDING));
			if (availableShallowBuildingRequireUpdate())
			{
				try {
					cAvailableShallowBuildings = downloadShallowBuildings();
					//joda version
					//cLastAvailableBuildingDownload = new DateTime();
					//java util version
					cLastAvailableBuildingDownload = new Date();
				}
				catch (Exception ex1) { //IOException will most likely be the cause
					Log.e(TAG, Log.getStackTraceString(ex1));
					downloadMsg = ex1.getCause() != null ? ex1.getCause().getMessage() : ex1.getMessage();
					setWifiStatus(STATUS_CONNECTION_PROBLEM);
					return null;
				}
			}
			//TODO: Change to find list of buildings. 
			//If there is only one candidate we treat it as building found and download it. 
			//In case of several we can say BUILDING_NOT_FOUND and give the user the choice between
			//the candidates (sorted after how many ap's were matched)
			Building correctShallowBuilding = getCorrectShallowBuilding(cAvailableShallowBuildings);
			
			//Test no building found:
			//Building correctShallowBuilding = null;
			//Test with specific dummy building:
			//String dummyName = "Cassiopeia"; 
			//Building correctShallowBuilding = getCorrectDummy(cAvailableShallowBuildings, dummyName);
			
			if (correctShallowBuilding == null)
			{
				setWifiStatus(STATUS_BUILDING_NOT_FOUND);			
			}
			else
			{
				CurrentBuilding = correctShallowBuilding;
				if (!buildingRequiresUpdate(CurrentBuilding.getBuildingID()))
				{
					setWifiStatus(STATUS_RADIOMAP_READY);
				}
				else
				{
					sendBroadcast(new Intent(PROGRESS_STATUS_DOWNLOADING_RADIOMAP));
					Building fullBuilding = null;
					try {
						//Hardcoded web client - consider dependency injection
						IWebClient webClient = new JsonWebClient();
						fullBuilding = webClient.downloadRadioMap(CurrentBuilding.getBuildingID());
					}
					catch (Exception ex2) {
						Log.e(TAG, Log.getStackTraceString(ex2));
						downloadMsg = ex2.getCause() != null ? ex2.getCause().getMessage() : ex2.getMessage();
					}
					if (fullBuilding != null)
					{
						CurrentBuilding.setGraphModel(fullBuilding.getGraphModel());
						//joda version:
						//cLastGraphDownloads.put(CurrentBuilding.getBuildingID(), new DateTime());
						//java util version:
						cLastGraphDownloads.put(CurrentBuilding.getBuildingID(), new Date());
						setWifiStatus(STATUS_RADIOMAP_DOWNLOADED);
					}
					else
					{
						setWifiStatus(STATUS_CONNECTION_PROBLEM);
					}	
				}				
			}  

			return null;
		}

		@Override
		protected void onPostExecute(Void arg)
		{
			cIsEnablingWifi = false;
			LocationService.this.notifyWifiStatusChanged(downloadMsg);
		}	
	}
	
	/**
	 * Class for clients to access.  
	 * @see Binder
	 */
	public class LocalBinder extends Binder {
		public LocationService getService() {
			return LocationService.this;
		}
	}
	
    /*
	private class WiFiScanReceiver extends BroadcastReceiver {
		//private int numScansToCreateMeasurement;
		private int scanNo;
		private WifiMeasurement lastMeasurement;
		private WifiPosEngine posEngine;
		//private Vertex currentEstimate;
		private EstimateResult currentEstimate;
		private AbsoluteLocation currentEstimateLocation;
		private Location currentEstimateAndroidLocation; 

		public WiFiScanReceiver(int useNumScans) {
			//this.numScansToCreateMeasurement = useNumScans;
			posEngine = new WifiPosEngine(CurrentBuilding); 
			lastMeasurement = new WifiMeasurement();
			currentEstimateAndroidLocation = new Location(PROVIDER_NAME);
		}

		@Override
		public void onReceive(Context c, Intent intent) {
			List<ScanResult> results = mWifiMan.getScanResults();
			for (ScanResult result : results) {
				//build measurement
				lastMeasurement.addValue(result.BSSID, result.level, new MacInfo(result));
			}

			scanNo++;
			if (scanNo >= getNumScansToCreateWiFiMeasurement())
			{
				//Make sure that we are always doing location estimation in the current building
				posEngine.setCurrentBuilding(CurrentBuilding);
				currentEstimate = posEngine.getEstimate(lastMeasurement);

				if (currentEstimate != null && currentEstimate.getVertex() != null)
				{
					//convert estimated location to android location
					currentEstimateLocation = currentEstimate.getVertex().getLocation().getAbsoluteLocation();
					currentEstimateAndroidLocation.setLatitude(currentEstimateLocation.getLatitude());
					currentEstimateAndroidLocation.setLongitude(currentEstimateLocation.getLongitude());
					currentEstimateAndroidLocation.setAltitude(currentEstimateLocation.getAltitude());
					currentEstimateAndroidLocation.setAccuracy((float)currentEstimate.getErrorEstimate());
					currentEstimateAndroidLocation.setTime(System.currentTimeMillis());
					Bundle extras = new Bundle();
			    	extras.putInt(BUNDLE_BUILDING_ID, CurrentBuilding.getBuildingID());
			    	extras.putInt(BUNDLE_VERTEX_ID, currentEstimate.getVertex().getId());
			    	currentEstimateAndroidLocation.setExtras(extras);			    	
			    	
					//notify listeners about new update
					notifyLocationChanged(currentEstimateAndroidLocation);					
				}

				//Prepare for new measurement
				scanNo = 0;
				lastMeasurement = new WifiMeasurement();
				setHasMovedSinceLastEstimate(false);
				try { Thread.sleep(getWifiPositioningUpdateInterval()); }
				catch (InterruptedException e) { }
			}
			//PROBLEM: Kommer aldrig fra l�kken. Skyldes formentlig at denne receiver og sensor k�rer samme tr�d
			while (!hasMovedSinceLastEstimate())
			{
				try { Thread.sleep(getWifiPositioningUpdateInterval()); }
				catch (InterruptedException e) { }
			}
			if (mWifiMan != null && mWifiMan.isWifiEnabled())
				mWifiMan.startScan();
			else
			{
				//TODO: Raise 'No Wi-Fi available'
				//Pt. stopper vi bare i stilhed
			}
		}	  
	}
	*/
	
	/**
	 * The algorithm that is used to compute position estimates. 
	 */
	private static IPositioningAlgorithm mCurrentWifiPositioningAlgorithm = new AlgorithmNNSS();
	
	/**
	 * Allow the positioning algorithm to be changed dynamically
	 * @param posAlgorithm The new positioning algorithm to use
	 */	
	public static synchronized void setWifiPositioningAlgorithm(IPositioningAlgorithm posAlgorithm)
	{
		if (posAlgorithm == null)
			throw new IllegalArgumentException("The Positioning algorithm cannot be null");
		mCurrentWifiPositioningAlgorithm = posAlgorithm;
	}
	public static synchronized IPositioningAlgorithm getWifiPositioningAlgorithm()
	{
		return mCurrentWifiPositioningAlgorithm;
	}
		
	private static final String TAG = "LocationService";
	
	/**
	 * Receives Wi-Fi signal strengths and computes location estimates using the WifiPosEngine. 
	 * @author rhansen
	 *
	 */
	private class WiFiScanReceiver extends BroadcastReceiver {
		//private int numScansToCreateMeasurement;
		private int scanNo;
		private WifiMeasurement lastMeasurement;
		private WifiPosEngine posEngine;
		//private Vertex currentEstimate;
		private EstimateResult currentEstimate;
		private AbsoluteLocation currentEstimateLocation;
		private Location currentEstimateAndroidLocation; 

		public WiFiScanReceiver(int useNumScans) {
			posEngine = new WifiPosEngine(CurrentBuilding, mCurrentWifiPositioningAlgorithm); 
			lastMeasurement = new WifiMeasurement();
			currentEstimateAndroidLocation = new Location(PROVIDER_NAME);
		}

		@Override
		public void onReceive(Context c, Intent intent) {
			List<ScanResult> results = mWifiMan.getScanResults();
			for (ScanResult result : results) {
				//build measurement
				lastMeasurement.addValue(result.BSSID, result.level, new MacInfo(result));
			}

			scanNo++;
			if (scanNo >= getNumScansToCreateWiFiMeasurement())
			{
				//Make sure that we are always doing location estimation in the current building
				posEngine.setCurrentBuilding(CurrentBuilding);
				currentEstimate = posEngine.getEstimate(lastMeasurement);

				if (currentEstimate != null && currentEstimate.getVertex() != null)
				{
					//convert estimated location to android location
					currentEstimateLocation = currentEstimate.getVertex().getLocation().getAbsoluteLocation();
					currentEstimateAndroidLocation.setLatitude(currentEstimateLocation.getLatitude());
					currentEstimateAndroidLocation.setLongitude(currentEstimateLocation.getLongitude());
					currentEstimateAndroidLocation.setAltitude(currentEstimateLocation.getAltitude());
					currentEstimateAndroidLocation.setAccuracy((float)currentEstimate.getErrorEstimate());
					currentEstimateAndroidLocation.setTime(System.currentTimeMillis());
					Bundle extras = new Bundle();
			    	extras.putInt(BUNDLE_BUILDING_ID, CurrentBuilding.getBuildingID());
			    	extras.putInt(BUNDLE_VERTEX_ID, currentEstimate.getVertex().getId());
			    	//extras.putIntArray(key, value)
			    	extras.putIntArray(BUNDLE_ESTIMATE_VERTICES_IDS, currentEstimate.getBcsVerticesIDs());
			    	extras.putDoubleArray(BUNDLE_ESTIMATE_SCORES, currentEstimate.getBcsScores());
			    	currentEstimateAndroidLocation.setExtras(extras);
					
			    	//We instruct the mWifiPositioningTask that a new estimate has been received
			    	//so now it can sleep the update interval before beginning the scanning operation again
			    	setHasJustReceivedEstimate(true);
			    	//We reset motion detection
					setHasMovedSinceLastEstimate(false);
										
					//notify listeners about new update
					notifyLocationChanged(currentEstimateAndroidLocation);					
				}

				//Prepare for new measurement
				scanNo = 0;
				lastMeasurement = new WifiMeasurement();
			}
			
			setIsReadyForNextWifiScan(true);
		}	  
	}

	/**
	 * The building that the user is currently estimated to be in (or null) 
	 */
	public static com.smartcampus.indoormodel.Building CurrentBuilding = null; 
	
	//joda versions:
	//private static HashMap<Integer, org.joda.time.DateTime> cLastGraphDownloads = new HashMap<Integer, org.joda.time.DateTime>();
	//private static org.joda.time.DateTime cLastAvailableBuildingDownload;
	//java util versions:
	private static HashMap<Integer, Date> cLastGraphDownloads = new HashMap<Integer, Date>();
	private static Date cLastAvailableBuildingDownload;
		
	private static ArrayList<Building> cAvailableShallowBuildings;

	/**
	 * The unique name of the Wi-Fi location provider
	 */
	public static final String PROVIDER_NAME = "com.smartcampus.android.location.WifiLocationProvider";
	
	//The following constants report on the radio map download progress
	public static final int STATUS_CONNECTION_PROBLEM = -1;
	public static final int STATUS_RADIOMAP_DOWNLOADED = 0;
	public static final int STATUS_RADIOMAP_NOT_DOWNLOADED = 1;
	public static final int STATUS_BUILDING_FOUND = 2;
	public static final int STATUS_BUILDING_NOT_FOUND = 3;	
	public static final int STATUS_RADIOMAP_READY = 4;
	public static final String BROADCAST_RADIOMAP_DOWNLOADED 		= "com.smartcampus.android.location.LocationService.BROADCAST_RADIOMAP_DOWNLOADED";
	public static final String BROADCAST_RADIOMAP_NOT_DOWNLOADED 	= "com.smartcampus.android.location.LocationService.BROADCAST_RADIOMAP_NOT_DOWNLOADED";
	public static final String BROADCAST_BUILDING_FOUND 			= "com.smartcampus.android.location.LocationService.BUILDING_FOUND";
	public static final String BROADCAST_BUILDING_NOT_FOUND			= "com.smartcampus.android.location.LocationService.BUILDING_NOT_FOUND";	
	public static final String PROGRESS_STATUS_DETERMINING_BUILDING = "com.smartcampus.android.location.LocationService.PROGRESS_STATUS_IDENTIFYING_BUILDING";
	public static final String PROGRESS_STATUS_DOWNLOADING_RADIOMAP = "com.smartcampus.android.location.LocationService.PROGRESS_STATUS_DOWNLOADING_RADIOMAP";
	
	private static boolean cIsEnablingWifi;
		
	public static void addAvailableBuilding(Building b)
	{
		cAvailableShallowBuildings.add(b);
	}
		
	public static Iterable<Building> getAvailableShallowBuildings()
	{
		return cAvailableShallowBuildings;
	}	
	private int mStatus;	
	
	
	//listeners for ordinary on-device position updates
	private ArrayList<LocationListener> locationListeners = new ArrayList<LocationListener>();
	//listeners for infrastructure-based position updates
	private ArrayList<LocationListener> infrastructureLocationListeners = new ArrayList<LocationListener>();
	
	protected WifiMeasurementBuilder mWifiSnifferService; //service used to create measurement
	protected boolean mIsWifiSnifferBound;    //checks whether the service is bound
	//For Wi-Fi scanning (maybe later refactor into the WifiSniffer class)
	private WifiManager mWifiMan;

	BroadcastReceiver mWifiReceiver;
	
	/******************* Android LocationProvider logic START *****************/

	public static final int AVAILABLE = 2;
	public static final int OUT_OF_SERVICE = 0;
	public static final int TEMPORARY_UNAVAILABLE = 1;
	
	//Holds a service connection to the WifiSniffer which is responsible for doing the actual measurement	
	private ServiceConnection mWifiSnifferConnection = new ServiceConnection()
	{
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			mWifiSnifferService = ((WifiMeasurementBuilder.LocalBinder)service).getService();	
			mIsWifiSnifferBound = true;
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			//wifiSnifferService = null;			
			mIsWifiSnifferBound = false;
		}		
	};

	// This is the object that receives interactions from clients.
	//More specifically, this is what allow clients to start/stop measuring and 
	//retrieve the result
	private final LocalBinder mBinder = new LocalBinder();

	private boolean doWifiPositioning;	
	private static int mWifiPositioningUpdateInterval = 2000; //2 second default
	
	//This is used to communicate back and forth between the mWifiPositioningTask and the WifiReceiver
	//When the WifiReceiver has just created a new location estimate, this value is set to true.
	//If the value is true, it means we sleep mWifiPositioningUpdateInterval before resuming 
	//the scanning operation.
	private volatile boolean mHasJustReceivedEstimate;
	protected synchronized boolean hasJustReceivedEstimate() {
		return mHasJustReceivedEstimate;
	}	
	protected synchronized void setHasJustReceivedEstimate(boolean isJustReceived) {
		mHasJustReceivedEstimate = isJustReceived;
	}
	
	//When a scan has been completed (and handled) we notify mWifiPositioningTask
	//that it can call startScan() again
	private volatile boolean mIsReadyFforNextWifiScan;
	protected synchronized void setIsReadyForNextWifiScan(boolean isReady)
	{
		mIsReadyFforNextWifiScan = isReady;
	}
	protected synchronized boolean isReadyForNextWifiScan()
	{
		return mIsReadyFforNextWifiScan;
	}
	
	private Runnable mWifiPositioningTask = new Runnable() {
		   @Override
		public void run() {
		   //enable wifi
		   if (!mWifiMan.isWifiEnabled())
			   if (mWifiMan.getWifiState() != WifiManager.WIFI_STATE_ENABLING)
				   mWifiMan.setWifiEnabled(true);			   
		   
		   setIsReadyForNextWifiScan(true);
		   
		   while (doWifiPositioning)
	       {
			   if (hasJustReceivedEstimate())
			   {
				   setHasJustReceivedEstimate(false);
				   try { Thread.sleep(getWifiPositioningUpdateInterval()); }
				   catch (InterruptedException e) { }
			   }
			   else if (hasMovedSinceLastEstimate())
			   {
				   //We do not start a scan until the previous one has completed
				   //maybe this step is redundant
				   if (isReadyForNextWifiScan())
				   {
					   setIsReadyForNextWifiScan(false);
					   mWifiMan.startScan(); //result is received in WiFiScanReceiver
					   Log.d(TAG, "scan initiated");
				   }
			   }
		   
			   try { Thread.sleep(100); } catch (InterruptedException e) { }
	       }			   	   
	   }
	};
		
	//Original mWifiPositioningTask - before motion detection introduction
	//IMPORTANT: Do not delete this before you are 100% sure that the above version works flawlessly!!
	/*
	private Runnable mWifiPositioningTask = new Runnable() {
		   @Override
		public void run() {
		   //enable wifi
		   if (!mWifiMan.isWifiEnabled())
			   if (mWifiMan.getWifiState() != WifiManager.WIFI_STATE_ENABLING)
				   mWifiMan.setWifiEnabled(true);			   
		   
		   //start running
		   int currentScanNo = 0; //Keep track of how many scans we have currently done
		   while (doWifiPositioning)
	       {
	    	   mWifiMan.startScan(); //result is received in WiFiScanReceiver
	    	   //TODO: Remove -> sleep after each scan
	    	   try { Thread.sleep(100); } catch (InterruptedException e) { } 
	    	   
	    	   currentScanNo++;
	    	   if (currentScanNo >= getNumScansToCreateWiFiMeasurement())
	    	   {
	    		   currentScanNo = 0; //reset scan number
	    		   try {
		    		   int sleepPeriod = getWifiPositioningUpdateInterval();
		    		   if (sleepPeriod < 0) //This should NEVER happen
		    			   sleepPeriod = 100;
		    		   Thread.sleep(sleepPeriod);
		    	   }
		    	   catch (InterruptedException e) { }
	    	   }		    	   
	       }			   	   
	   }
	};
	*/
	
	private boolean doInfrastructureWifiPositioning;
	private static int mInfrastructureWifiPositioningUpdateInterval = 2000;
	
	private String mMacAddress;
	/**
	 * Gets the client's mac address
	 * @return The client's mac address.
	 */
	public synchronized String getMacAddress()
	{
		if (mMacAddress == null)
		{
			WifiManager wifiMan = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
						
			//Wi-Fi needs to be turned on in order to get mac address
			//If we turn it on, we turn it back off afterwards
			boolean prevWifiStatus = wifiMan.isWifiEnabled();
			boolean curWifiStatus = prevWifiStatus;
			if (prevWifiStatus == false)
			{
				curWifiStatus = wifiMan.setWifiEnabled(true);
			}
			mMacAddress = wifiMan.getConnectionInfo().getMacAddress();
			if (curWifiStatus != prevWifiStatus)
				wifiMan.setWifiEnabled(prevWifiStatus);
		}	
		return mMacAddress;		
	}		
	
	private Runnable mInfrastructureWifiPositioningTask = new Runnable() {
		@Override
		public void run() {
		   //wifi sniffer service start 		
		   
		   IInfrastructurePositioningService svc = new JsonInfrastructurePositioningClient();
		   
		   //Start positioning
		   String mac = getMacAddress();
		   svc.startWifiPositioning(mac);
		   
		   //Repetedly call GetPosition(); transform the result to an Android location, and then notify listeners
		   while (doInfrastructureWifiPositioning)
	       {		    	   
	    	   try { Thread.sleep(mInfrastructureWifiPositioningUpdateInterval); }
	    	   catch (InterruptedException e) { } 
	    	   
	    	   InfrastructurePositionEstimate pos = svc.getPosition(mac);	
	    	   //InfrastructurePositionEstimate pos = svc.testGetRandomPosition(clientMacAddress);
	    	   
	    	   if (pos != null)
	    	   {
	    		   //transform result
		    	   Location androidPos = new Location(TRACKING_PROVIDER_WIFI_INFRASTRUCTURE);
		    	   androidPos.setLatitude(pos.getLatitude());
		    	   androidPos.setLongitude(pos.getLongitude());
		    	   androidPos.setAltitude(pos.getAltitude());
		    	   androidPos.setAccuracy((float) pos.getAccuracy());
		    	   androidPos.setBearing((float) pos.getBearing());
		    	   androidPos.setSpeed((float) pos.getSpeed());
		    	   androidPos.setTime(System.currentTimeMillis());
		    	   Bundle extras = new Bundle();
		    	   extras.putInt(BUNDLE_BUILDING_ID, pos.getBuildingId());
		    	   extras.putInt(BUNDLE_VERTEX_ID, pos.getVertexId());
		    	   androidPos.setExtras(extras);
		    	   
		    	   //notify listeners
		    	   notifyInfrastructureLocationChanged(androidPos);
	    	   }
	       } 
		}
	};	
	
	/**
	 * Add a listener for on-device based location updates
	 * @param listener 
	 */
	public void addLocationListener(LocationListener listener)
	{
		locationListeners.add(listener);
	}
	
	/**
	 * Add a listener for infrastructure based location updates
	 * @param listener
	 */
	public void addInfrastructureLocationListener(LocationListener listener)
	{
		infrastructureLocationListeners.add(listener);
	}

	/**
	 * Manually request that a building with a given building id should be downloaded
	 * @param buildingId The id of the building to download
	 */
	public void downloadRadioMapManually(int buildingId)
	{
		new DownloadGraphTask().execute(buildingId);		
	}

	private ArrayList<Building> downloadShallowBuildings() throws java.io.IOException
	{
		
		//IWebClient webClient = new OData4jWebClient();
		IWebClient webClient = new JsonWebClient();
		
		//may throw IOException
		Iterable<Building> downloadedBuildings = webClient.getShallowBuildings();
		
		ArrayList<Building> result = new ArrayList<Building>();
		for (Building b : downloadedBuildings)
		{
			//ignore the vertex graveyard (has id 18 and name 'VERTEX_GRAVEYARD')
			if (b.getName() != null && (b.getName().startsWith("VERTEX_GRAVEYARD") || b.getName().startsWith("DUMMY_")))
				continue;
			else	
				result.add(b);
		}
		return result;
		
	}

	/**
	 * Called to enable indoor positioning (i.e., download an appropriate radio map, such that Wi-Fi positioning can be started).  
	 */
	public void enableIndoorPositioning()
	{		
		//check if we are already in the process of enabling wifi
		if (cIsEnablingWifi)
			return;
		else
		{
			new EnableIndoorPositioningTask().execute();
		}
	}

	//@Override
	public int getAccuracy() {		
		return 0;
	}

	@SuppressWarnings("unused")
	private Building getCorrectDummy(Iterable<Building> shallowBuildings, int buildingId)
	{
		for (Building b : shallowBuildings)
		{
			if (b.getBuildingID() == buildingId)
				return b;
		}
		return null;
	}
	
	@SuppressWarnings("unused")
	private Building getCorrectDummy(Iterable<Building> shallowBuildings, String bName)
	{
		for (Building b : shallowBuildings)
		{
			if (b.getName().contains(bName))
				return b;
		}
		return null;
	}
	
	private Building getCorrectShallowBuilding(Iterable<Building> shallowBuildings) {
		//consider strategy pattern		
		return getNearestAPMatchBuilding(shallowBuildings);
	}

	private Building getNearestAPMatchBuilding(Iterable<Building> buildings)
	{
		if (buildings == null)
			return null;

		Building bestMatch = null;
		//get a measurement to compare to the buildings' aps
		WifiMeasurement meas = this.getWifiMeasurement2();
		if (meas == null)
			return null; //throw new Exception("No measurement");

		//find best match by matching number of common APs
		int maxCommonAPs = 0;		
		for (Building curBuilding : buildings)
		{			
			int commonAPs = getNumberOfIdenticalMacs(meas.getMACs(), curBuilding.getPermissableAPs());
			if (commonAPs > maxCommonAPs)
			{
				maxCommonAPs = commonAPs;
				bestMatch = curBuilding;
			}
		}		
		return bestMatch;
	}

	private int getNumberOfIdenticalMacs(Set<String> measAPs, List<String> buildingAPs)
	{
		int numMatches = 0;
		for (String curA : measAPs)
		{
			if (buildingAPs.contains(curA))
				numMatches++;
		}
		return numMatches;
	}
	
	//@Override
	public int getPowerRequirement() {
		return 0;
	}

	/******************* Android LocationProvider logic END *****************/
		
	private WifiMeasurement getWifiMeasurement2()
	{
		if (mWifiMan == null)
		{
			mWifiMan = (WifiManager) getSystemService(Context.WIFI_SERVICE);
			
		}
		//enable wifi
	   if (!mWifiMan.isWifiEnabled())
		   if (mWifiMan.getWifiState() != WifiManager.WIFI_STATE_ENABLING)
			   mWifiMan.setWifiEnabled(true);
	   
	   CorrectBuildingWifiReceiver rec = new CorrectBuildingWifiReceiver(2);
	   registerReceiver(rec, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));        
       //start running
	   mWifiMan.startScan();
	   int numTries = 0;
	   //wait for result
	   while (!rec.isDone() && numTries < 10)
       {
    	   try {
    		   Thread.sleep(1000);
    	   }
    	   catch (InterruptedException e) {
    		   e.printStackTrace();
    	   }
    	   numTries++;
       }
	      
	   WifiMeasurement res = rec.getMeasurement();
	   try
	   {
		   unregisterReceiver(rec);
		   
	   }
	   catch (IllegalArgumentException e) { }
	   rec = null;
	   
	   return res;
	}
	
	/**
	 * Gets the update interval between consecutive Wi-Fi position estimates
	 * @return The update interval
	 */
	public static synchronized int getWifiPositioningUpdateInterval()
	{
		return mWifiPositioningUpdateInterval;
	}	

	/**
	 * Gets the update interval between consecutive infrastructure-based Wi-Fi position estimates 
	 * @return
	 */
	public static synchronized int getInfrastructureWifiPositioningUpdateInterval()
	{
		return mInfrastructureWifiPositioningUpdateInterval;
	}
	
	public int getWifiStatus()
	{
		return mStatus;
	}
	
	//@Override
	public boolean hasMonetaryCost() {
		return true;
	}

	/**
	 * Reports on whether we are currently doing Wi-Fi positioning
	 * @return
	 */
	public boolean isDoingWifiPositioning()
    {
    	return doWifiPositioning;
    }
	
	/**
	 * Reports on whether we are currently enabling Wi-Fi positioning
	 * (i.e., in the process of identifying and downloading an appropriate radio map) 
	 * @return
	 */
	public boolean isEnablingWifiPositioning()
	{
		return cIsEnablingWifi;
	}

	/**
	 * The following values are used to add VertexId and BuildingId information to indoor location estimates
	 */
	public static final String BUNDLE_VERTEX_ID = "vertexId";
	public static final String BUNDLE_BUILDING_ID = "buildingId";
	public static final String BUNDLE_ESTIMATE_VERTICES_IDS = "estimateVertexIds";
	public static final String BUNDLE_ESTIMATE_SCORES = "estimateScores";
	
	//Notify about a new Wifi (DEVICE-based) location estimate (using Android location)
	private void notifyLocationChanged(Location newLocation)
	{
		if (locationListeners == null)
			return;

		for (LocationListener listener : locationListeners)
		{
			listener.onLocationChanged(newLocation);
		}
		
		if (isTrackingAllowed())
        {
			int vertexId = newLocation.getExtras().getInt(BUNDLE_VERTEX_ID);
			int buildingId = newLocation.getExtras().getInt(BUNDLE_BUILDING_ID);
			AddToTrackedPositions(CreateTrackedPosition(
					newLocation, TRACKING_PROVIDER_WIFI_DEVICE, buildingId, vertexId));
        }
	}	

	//Notify about a new Wifi (INFRASTRUCTURE-based) location estimate (using android location)
	private void notifyInfrastructureLocationChanged(Location newLocation)
	{
		if (infrastructureLocationListeners == null)
			return;
	
		for (LocationListener listener : infrastructureLocationListeners)
		{
			listener.onLocationChanged(newLocation);
		}
		
		if (isTrackingAllowed())
        {
			int vertexId = newLocation.getExtras().getInt(BUNDLE_VERTEX_ID);
			int buildingId = newLocation.getExtras().getInt(BUNDLE_BUILDING_ID);
			AddToTrackedPositions(CreateTrackedPosition(
					newLocation, TRACKING_PROVIDER_WIFI_INFRASTRUCTURE, buildingId, vertexId));
        }
	}
	
	private void notifyWifiStatusChanged(String msg)
	{
		if (locationListeners == null)
			return;

		Bundle b = new Bundle();
		b.putString("Msg", msg);
		for (LocationListener listener : locationListeners)
		{
			listener.onStatusChanged(PROVIDER_NAME, getWifiStatus(), b);
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	//called once (before onStartCommand() or onBind().
	//If the service is already running, this is not called
	@Override
	public void onCreate() {
		super.onCreate();
		mWifiMan = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		//Bind WifiSniffer
		//Intent bindIntent = new Intent(this, WifiSniffer.class);
		//bindService(bindIntent, mWifiSnifferConnection, Context.BIND_AUTO_CREATE);	
		
		//enableIndoorPositioning();
		initializeAccelerometer();
	}	
	
	/**
	 * Close threads and unregister services
	 */
	@Override
	public void onDestroy() {
		super.onDestroy();
		//unbind service
		if (mIsWifiSnifferBound)
		{
			unbindService(mWifiSnifferConnection);
			mIsWifiSnifferBound = false;
		}	
		
		mSensorManager.unregisterListener(this);
	}

	/**
	 * Unsubscribe a listener from receiving further on-device based location updates
	 * @param listener
	 * @return
	 */
	public boolean removeLocationListener(LocationListener listener)
	{
		return locationListeners.remove(listener);
	}

	/**
	 * Unsubscribe a listener from receiving further infrastructure based location updates
	 * @param listener
	 * @return
	 */
	public boolean removeInfrastructureLocationListener(LocationListener listener)
	{
		return infrastructureLocationListeners.remove(listener);
	}
	
	//@Override
	public boolean requiresCell() {
		return false;
	}

	//@Override
	public boolean requiresNetwork() {
		return true;
	}	
	
	//@Override
	public boolean requiresSatellite() {
		return false;
	}
	
	/**
	 * This setter is used to define the Wi-Fi positioning update interval, i.e., the interval between consecutive position estimates. 
	 * @param updateIntervalInMilliseconds The update interval in milliseconds. The lowest allowed value is 1000 (one second)
	 */
	public static synchronized void setWifiPositioningUpdateInterval(int updateIntervalInMilliseconds)
	{
		if (updateIntervalInMilliseconds >= 1000)
			mWifiPositioningUpdateInterval = updateIntervalInMilliseconds;
		else
			mWifiPositioningUpdateInterval = 1000;
	}	
	
	/**
	 * This setter is used to define the positioning update interval of infrastructure-based positioning. 
	 * @param updateIntervalInMilliseconds The update interval in milliseconds. The lowest allowed value is 1000 (one second)
	 */	
	public static synchronized void setWifiInfrastructurePositioningUpdateInterval(int updateIntervalInMilliseconds)
	{
		if (mInfrastructureWifiPositioningUpdateInterval >= 1000)
			mWifiPositioningUpdateInterval = updateIntervalInMilliseconds;
		else
			mWifiPositioningUpdateInterval = 1000;
	}	
	
	
	public void setWifiStatus(int wifiStatus)
	{
		mStatus = wifiStatus;
	}
	
	/**
	 * Starts Wi-Fi positioning with the following default values: 
	 * - A three second update interval
	 * - Two scanning operations are used to construct a Wi-Fi measurement
	 */
	public void startWifiPositioning()
	{
		//startWifiPositioning(2); //This was the first configuration - good, but somewhat jumpy
		startWifiPositioning(2, 3000);		
	}
	
	/**
	 * Starts Wi-Fi positioning with a default three-second update interval
	 * @param numScansToUse The number of scanning operations used to construct a Wi-Fi measurement
	 */
	public void startWifiPositioning(int numScansToUse) {
    	startWifiPositioning(numScansToUse, 3000); //This was the first configuration - good, but somewhat jumpy
		
    }	
	
	/**
	 * Starts Wi-Fi positioning with the specified parameters.
	 * @param numScansToUse The number of scanning operations used to construct a Wi-Fi measurement
	 * @param updateIntervalInMilliseconds The update interval (in milliseconds) between consecutive position updates.
	 */
    public void startWifiPositioning(int numScansToUse, int updateIntervalInMilliseconds)
    {
    	//undo any previous positioning
    	if (isDoingWifiPositioning())
    	{
    		stopWifiPositioning();
    	}
    	
    	doWifiPositioning = true;
    	setWifiPositioningUpdateInterval(updateIntervalInMilliseconds);    
    	setNumScansToCreateWiFiMeasurement(numScansToUse);
    	//We pause when no movement is detected, so we force at least one estimate
    	setHasMovedSinceLastEstimate(true);
    	
    	// Register Broadcast Receiver
		if (mWifiReceiver == null)
			mWifiReceiver = new WiFiScanReceiver(numScansToUse);
        registerReceiver(mWifiReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));        
        
        new Thread(mWifiPositioningTask).start();  
    }
    
    /**
     * Starts infrastructure-based positioning with the specified update interval
     * @param updateIntervalInMilliseconds The update interval (in milliseconds) between consecutive infrastructure-based position estimates
     */
    public void startWifiInfrastructurePositioning(int updateIntervalInMilliseconds)
    { 
    	doInfrastructureWifiPositioning = true;
    	setWifiInfrastructurePositioningUpdateInterval(updateIntervalInMilliseconds);
    	new Thread(mInfrastructureWifiPositioningTask).start();        
    }  
   
    
    private static int mNumScansToUseToCreateWiFiMeasurement = 2; 
    /**
	 * This setter is used to specify the number of scan operations that should be used to create a Wi-Fi Measurement.
	 * Creating a Wi-Fi measurement from several scans means that signal strength values are averaged over the number of scans. 
	 * Using several scans tends to produces more stable results.
	 * Typically, values between 1 and 5 are suitable (NOTE: Higher values may produce undesired lag in estimating the users position online). 
	 * The default (and recommended) value is 3	 * 
	 * @param numScansToUse The number of scans to use to create a Wi-Fi measurement. The minimum number is 1.
	 */
    public synchronized static void setNumScansToCreateWiFiMeasurement(int numScansToUse) {
		if (numScansToUse >= 1)
			mNumScansToUseToCreateWiFiMeasurement = numScansToUse;		
		else 
			mNumScansToUseToCreateWiFiMeasurement = 1;	
	}
    public static int getNumScansToCreateWiFiMeasurement() {
    	return mNumScansToUseToCreateWiFiMeasurement;
    }

    /**
     * Stops Wi-Fi positioning. Wi-Fi positioning can be resumed with a call to StartWifiPositioning. 
     * If much time has passed since Wi-Fi positioning was stopped until it is restarted, it is advisable to 
     * call enableWifiPositioning() first in order to download an appropriate radio map first. 
     */
	public void stopWifiPositioning() {
        doWifiPositioning = false;
		if (mWifiReceiver != null)
        {
			try
			{
				unregisterReceiver(mWifiReceiver);
				mWifiReceiver = null;
			} catch(IllegalArgumentException e) { }        	        	
        }        
    }
    
	/**
	 * Stops Infrastructure-based positioning. 
	 */
	public void stopInfrastructureWifiPositioning()
	{
		doInfrastructureWifiPositioning = false;
		
		new Thread(
			new Runnable() {
				@Override
				public void run() {
					String mac = getMacAddress();
				    IInfrastructurePositioningService svc = new JsonInfrastructurePositioningClient();
				    svc.stopMeasuring(mac);
				}
			}).start();
	}
	
	private static boolean mAllowTracking;
	/**
	 * If tracking is allowed, the estimated positions are shared with the SmartCampusAAU backend. 
	 * @param mAllowTracking the mAllowTracking to set
	 */
	public synchronized static void setTrackingAllowed(boolean allowTracking) {
		mAllowTracking = allowTracking;
	}

	/**
	 * Specifies whether tracking is currently allowed
	 * @return the mAllowTracking
	 */
	public synchronized static boolean isTrackingAllowed() {
		return mAllowTracking;
	}
	
//////////////////////////// [REGION-start: Tracking] /////////////////////////////////////	
	//As it is, gps is not contained in this LocationService (as it would just be an extra layer of indirection)
	public static final String TRACKING_PROVIDER_GPS = "Android Location Manager (Gps)";
	private static final String TRACKING_PROVIDER_WIFI_DEVICE = "Android Device Wifi";
	private static final String TRACKING_PROVIDER_WIFI_INFRASTRUCTURE = "Android Infrastructure Wifi";

	
	private static String mClientId; //A unique id that identifies the user for tracking purposes
	public static void setClientId(String clientId) {
		if (clientId != null && clientId.length() > 0)
			mClientId = clientId;		
	}
	/**
	 * Returns the client device's (anonymous) unique id. If it has not been set by a user, then we 
	 * automatically generate a unique id. 
	 * @return
	 */
	public static String getClientId() {
		if (mClientId == null || mClientId.length() < 1)
		{
			mClientId = java.util.UUID.randomUUID().toString();
		}		
		return mClientId;
	}	
	
	private ArrayList<TrackedPosition> bufferedTrackedPositions = new ArrayList<TrackedPosition>();
    private static final int BUFFERED_POSITION_ESTIMATES_FLUSH_COUNT = 3;
    //Used to lock the bufferedTrackedPositions as we may be writing concurrently
    //(from wi-fi, gps, and we may be clearing the buffer)
    private Object bufferedTrackedPositionsLock = new Object();

	/**
     * Add a position estimate to the buffer of tracked positions. 
     * When the buffer is full, the data are flushed to the backend
     * @param pos
     */
    public void AddToTrackedPositions(TrackedPosition pos)
    {
        // Wi-Fi and GPS can potentially be adding at the same time so we lock the buffer.
        synchronized (bufferedTrackedPositionsLock)
        {
            bufferedTrackedPositions.add(pos);
        }
        if (IsTimeToSendTrackingData())
        {
            UploadTrackingDataAndFlushBuffer();
        }    
    }
    
    private boolean IsTimeToSendTrackingData()
    {
        return bufferedTrackedPositions.size() >= BUFFERED_POSITION_ESTIMATES_FLUSH_COUNT;
    }
	
    private void UploadTrackingDataAndFlushBuffer()
    {
        if (bufferedTrackedPositions == null)
            return;
        if (bufferedTrackedPositions.size() == 0)
            return;
                    
        //copy tracking data and clear buffer
        final TrackedPosition[] tmp;
        synchronized (bufferedTrackedPositionsLock)
        {
            int numPositions = bufferedTrackedPositions.size();
            tmp = new TrackedPosition[numPositions];
            for (int i = 0; i < numPositions; i++)
            {
            	tmp[i] = bufferedTrackedPositions.get(i);
            }
            bufferedTrackedPositions.clear();
        }
        
        new Thread(
    			new Runnable() {
    				@Override
    				public void run() {
    				   //perform batch update of tracked positions
    					BatchUpdater.updateTrackedPositions(tmp);
    				}
    			}).start();        
        
    }
    
    /**
     * Used by outdoor positions who do not have a concept of buildingIds and vertexIds
     * @param org
     * @param provider
     * @return
     */
    public static TrackedPosition CreateTrackedPosition(Location org, String provider)
    {
    	final int errorVal = -999;
        return CreateTrackedPosition(org, provider, errorVal, errorVal);
    }
    
    /**
     * Creates a tracked position (which also has an attached client id)
     * @param org
     * @param provider
     * @param buildingId
     * @param vertexId
     * @return
     */
    private static TrackedPosition CreateTrackedPosition(Location org, String provider, int buildingId, int vertexId)
    {
    	TrackedPosition res = new TrackedPosition();
        //NaN numbers cause an exception on MSSQL Server so they must be avoided.
        
        boolean hasAccuracy = false;
        boolean hasBearing = false;
        boolean hasSpeed = false;
        if (!Float.isNaN(org.getAccuracy()))
        {
            res.setAccuracy(org.getAccuracy());
            hasAccuracy = true;
        }
        if (!Float.isNaN(org.getBearing()))
        {
            res.setBearing(org.getBearing());
            hasBearing = true;
        }
        if (!Float.isNaN(org.getSpeed()))
        {
            res.setSpeed(org.getSpeed());
            hasSpeed = true;
        }
        res.setBuildingId(buildingId);
       
        //Latitude, longitude, altitude should never be NaN's
        res.setLatitude(org.getLatitude());
        res.setLongitude(org.getLongitude());
        res.setAltitude(org.getAltitude());
        
        res.setProvider(provider); 
        res.setTime(System.currentTimeMillis());
        res.setVertexId(vertexId);
        res.setHasAccuracy(hasAccuracy);
        res.setHasBearing(hasBearing);
        res.setHasSpeed(hasSpeed);
        res.setClientId(getClientId()); //A guid

        return res;
    }    
   
////////////////////////////[REGION-end: Tracking] /////////////////////////////////////	

    //@Override
	public boolean supportsAltitude() {
		return true;
	}

    //@Override
	public boolean supportsBearing() {
		return false;
	}	
	
	//@Override
	public boolean supportsSpeed() {
		return false;
	}

////////////////////////////ACCELEROMETER //////////////////////////////////////
	
	//se http://www.techrepublic.com/blog/app-builder/a-quick-tutorial-on-coding-androids-accelerometer/472
	private SensorManager mSensorManager;
	private Sensor mAccelerometer;
	
	float mAccelerometerLastX, mAccelerometerLastY, mAccelerometerLastZ; //Sidst registrerede m�linger
	static final float NOISE = (float)0.25; //2.0; 
	private volatile boolean mHasMoved;
	
	private void initializeAccelerometer() {
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
	}
	
	private synchronized void setHasMovedSinceLastEstimate(boolean hasMoved)
	{
		mHasMoved = hasMoved;
	}
	private synchronized boolean hasMovedSinceLastEstimate()
	{
		return mHasMoved;
	}
	
	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        
        float deltaX = Math.abs(mAccelerometerLastX - x);
        float deltaY = Math.abs(mAccelerometerLastY - y);
        float deltaZ = Math.abs(mAccelerometerLastZ - z);
        
        if (deltaX > NOISE || deltaY > NOISE || deltaZ > NOISE)
        {
        	setHasMovedSinceLastEstimate(true);
        }
        mAccelerometerLastX = x;
        mAccelerometerLastY = y;
        mAccelerometerLastZ = z;		
	}	
}
