package org.mdpnp.apps.testapp.patient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.mdpnp.apps.testapp.Main;
import org.mdpnp.apps.testapp.patient.PatientInfo.Gender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue.ValueType;

/**
 * This version of OpenEMRImpl works with &qout;modern&qout; versions of OpenEMR (7.2 at
 * the time of writing) and rather than the previous version, which used a username and
 * password to login, this variant uses standardised APIs including using an "application"
 * ID, secret and access/refresh tokens to get access to the data in OpenEMR.
 */
public class OpenEMRImpl extends EMRFacade {
	
	private static final Logger log = LoggerFactory.getLogger(Main.class);
	
	private String openEMRURL;
	private String accessToken;
	long expiryTime;
	//Next four fields are set by reading properties file
	private String refreshToken;
	private String clientId;
	private String clientSecret;
	private String scope;
	
	/**
	 * This properties needs to be class wide because the refresh token gets refreshed (confusing!)
	 * when the refresh token is used to get an access token.  So we always need to save the updated
	 * one
	 */
	private Properties p;

	public OpenEMRImpl(Executor executor) {
		super(executor);
		loadProps();
		emrType=EMRType.OPENEMR;
	}

	public OpenEMRImpl(ListHandler handler) {
		super(handler);
		loadProps();
	}
	
	public OpenEMRImpl() {
		super(NOOP_HANDLER);
		loadProps();
	}

	private void loadProps() {
		p=new Properties();
		String userHome=System.getProperty("user.home");
		File f=new File(userHome,"iceopenemr.properties");
		if( ! f.exists() || ! f.canRead() ) {
			log.error("iceopenemr.properties is not accessible in user home directory");
			return;
		}
		try {
			p.load(new FileInputStream(f));
			this.clientId=p.getProperty("clientid");
			this.refreshToken=p.getProperty("refreshtoken");
			this.clientSecret=p.getProperty("clientsecret");
			this.scope=p.getProperty("scope");
		} catch (Exception e) {
			log.error("Could not read iceopenemr.properties in user home directory",e);
		}
	}
	
	public void setUrl(String url) {
		openEMRURL=url;
	}
	
	public String getUrl() {
		return openEMRURL;
	}

	@Override
	List<PatientInfo> fetchAllPatients() {
		List<PatientInfo> returnList=new ArrayList<>();
		if(accessToken==null || expired()) {
			try {
				getAccessToken();
			} catch (Exception ex) {
				log.error("Could not log in to OpenEMR", ex);
				return returnList;
			}
		}
		try {
			addOpenEMRPatients(returnList);
		} catch (Exception ex) {
			log.error("Could not retrieve patient list from OpenEMR", ex);
		}
		return returnList;
	}
	
	private void addOpenEMRPatients(List<PatientInfo> returnList) throws Exception {
		HttpClient httpClient=getHttpClient();
		//It's just a get with no body...
		HttpRequest patientRequest=HttpRequest.newBuilder().uri(
			new URI("https://"+openEMRURL+"/apis/"+scope+"/api/patient")
		).header("Authorization", "Bearer "+accessToken)
		.build();
		HttpResponse<byte[]> responseBytes=httpClient.send(patientRequest, BodyHandlers.ofByteArray());
		ByteArrayInputStream bais=new ByteArrayInputStream(responseBytes.body());
		System.err.println("patient query response body is "+new String(responseBytes.body()));
		JsonReader patientReader=Json.createReader(bais);
		//patientReader now starts with an Object.
		JsonObject resultsRoot=patientReader.readObject();
		JsonArray patientArray=(JsonArray)resultsRoot.get("data");
		SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd");
		patientArray.forEach( v -> {
			if(v.getValueType().equals(ValueType.OBJECT)) {
                JsonObject jo=(JsonObject)v;
                Gender gender;
                //TODO: Check these "sex" values from OpenEMR.
                if(jo.getString("sex").equals("Male")) {
                	gender=Gender.M;
                } else {
                	gender=Gender.F;
                }
                Date d=null;
                try {
                	d=df.parse(jo.getString("DOB"));
                } catch (ParseException pe) {
                	log.error("Could not parse date "+jo.getString("DOB"), pe);
                	d=new Date(0);
                }
                OpenEMRPatientInfo pi=new OpenEMRPatientInfo(
            		String.valueOf(jo.getInt("id")),
            		jo.getString("fname"),
            		jo.getString("lname"),
            		gender,
            		d,
            		jo.getString("uuid")
        		);
                returnList.add(pi);
            }
			
		});
		
        log.info("OpenEMRImpl has "+patientArray.size()+" patients");
        
	}
	
	/**
	 * A trust manager that ignores certificate errors.
	 */
	private static final TrustManager MOCK_TRUST_MANAGER = new X509ExtendedTrustManager() {
		   @Override
		   public java.security.cert.X509Certificate[] getAcceptedIssuers() {
		       return new java.security.cert.X509Certificate[0];
		   }

		   @Override
		   public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
		       // empty method
		   }
		   // ... Other void methods

		   @Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			// TODO Auto-generated method stub
			
		}

		   @Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
				throws CertificateException {
			// TODO Auto-generated method stub
			
		}

		   @Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
				throws CertificateException {
			// TODO Auto-generated method stub
			
		}

		   @Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			// TODO Auto-generated method stub
			
		}

		   @Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			// TODO Auto-generated method stub
			
		}
	};
	
	/**
	 * Get a HTTP client.  We do this because we need it in multiple places, but we also need
	 * to configure it to ignore SSL errors.
	 * 
	 * @return HttpClient that ignores SSL errors and can be used with HttpRequest
	 * @throws Exception
	 */
	private HttpClient getHttpClient() throws Exception {
		SSLContext sslContext=SSLContext.getInstance("SSL");
		sslContext.init(null, new TrustManager[] {MOCK_TRUST_MANAGER}, new SecureRandom());
		HttpClient httpClient=HttpClient.newBuilder().sslContext(sslContext).build();
		return httpClient;
	}
	
	/**
	 * Gets an access token for OpenEMR using the URL and parameters in the fields.
	 * 
	 * This uses a refresh token, client id and client secret to get an up-to-date
	 * access token.  This new access token is then stored in the accessToken field,
	 * and the new expiryTime is set.
	 * 
	 */
	private void getAccessToken() throws Exception {
		HttpClient httpClient=getHttpClient();
		
		StringBuilder bodyBuilder=new StringBuilder("grant_type=refresh_token&");
		bodyBuilder.append("refresh_token=").append(refreshToken)
		.append("&client_id=").append(clientId)
		.append("&client_secret=").append(clientSecret);
		//Now we have a client, build a request for it.
		HttpRequest tokenRequest=HttpRequest.newBuilder().uri(
			new URI("https://"+openEMRURL+"/oauth2/default/token")
		).header("Content-Type","application/x-www-form-urlencoded")
		.POST(
			HttpRequest.BodyPublishers.ofString(bodyBuilder.toString())
		)
		.build();
		
		HttpResponse<byte[]> refreshResponse=httpClient.send(tokenRequest, BodyHandlers.ofByteArray());
		byte[] responseBytes=refreshResponse.body();
		System.err.println("request body is "+bodyBuilder.toString());
		System.err.println("responseBytes length is "+responseBytes.length);
		System.err.println("response is "+new String(responseBytes));
		JsonReader reader=Json.createReader(new ByteArrayInputStream(responseBytes));
		JsonObject tokenObject=reader.readObject();
		
		String accessToken=tokenObject.getString("access_token");
        this.accessToken=accessToken;
        long duration=tokenObject.getInt("expires_in");
        expiryTime=System.currentTimeMillis()+(duration*1000);
        
        System.err.println("OpenEMR new access token is "+accessToken.substring(0, 20)+"... expiring in "+duration);
        /*
         * It seems that this tokenObject from response includes a NEW refreshToken.
         * So we store that in the properties.  Have tested this by storing on a Friday,
         * and on Monday morning everything still worked.
         */
        this.refreshToken=tokenObject.getString("refresh_token");
        p.setProperty("refreshtoken", this.refreshToken);
        String userHome=System.getProperty("user.home");
		File f=new File(userHome,"iceopenemr.properties");
        p.store(new FileOutputStream(f), "ICE OpenEMR Properties, refreshed at "+new Date().toString());
        //TODO: How to get a new refresh token from scratch - maybe just another perms request with offline_access in it?
	}
	
	/**
	 * Assuming the expiry time is 1 hour in OpenEMR API,
	 * we calculate if the login has expired.  Later, introduce
	 * some sort of margin for this so that we say expired if
	 * there is only one minute on the clock or similar. 
	 */
	private boolean expired() {
		if(System.currentTimeMillis() < expiryTime) {
			//We are good
			return false;
		}
		return true;
	}

	@Override
	public void deleteDevicePatientAssociation(DevicePatientAssociation assoc) {
		// TODO Auto-generated method stub

	}

	@Override
	public DevicePatientAssociation updateDevicePatientAssociation(DevicePatientAssociation assoc) {
		//This does nothing for now...
		return assoc;
	}
	
	/*
	 * Following methods are ones that logically belong here as although they are not part of EMRFacade,
	 * we want to be able to use the same connectivity methods that we already have here, without "giving
	 * out" that connection info.  So any interaction with OpenEMR ultimately occurs here.
	 */
	
	public ArrayList<PatientEncounter> getEncountersForPatient(OpenEMRPatientInfo pi) throws Exception {
		HttpClient httpClient=getHttpClient();
		String uuid=pi.getUUID();
		HttpRequest request=HttpRequest.newBuilder().uri(
				new URI("https://"+openEMRURL+"/apis/"+scope+"/api/patient/"+uuid+"/encounter")
			).header("Authorization", "Bearer "+accessToken)
			.build();
		//TODO: More abstraction - this client, request, response combination is common.
		HttpResponse<byte[]> responseBytes=httpClient.send(request, BodyHandlers.ofByteArray());
		ByteArrayInputStream bais=new ByteArrayInputStream(responseBytes.body());
		System.err.println("patient query response body is "+new String(responseBytes.body()));
		JsonReader reader=Json.createReader(bais);
		JsonObject resultsRoot=reader.readObject();
		JsonArray resultsArray=(JsonArray)resultsRoot.get("data");
		ArrayList<PatientEncounter> returnList=new ArrayList<>(); 
		resultsArray.forEach( v -> {
			if(v.getValueType().equals(ValueType.OBJECT)) {
                JsonObject jo=(JsonObject)v;
                PatientEncounter encounter=new PatientEncounter();
                encounter.date=jo.getString("date");
                encounter.reason=jo.getString("reason");
                encounter.eid=jo.getInt("eid");
                encounter.euuid=jo.getString("euuid");
                //encounter.facility=jo.getString("facility");
                returnList.add(encounter);
			}
		});	//End of forEach across results
		return returnList;
	}
	
	public static class PatientEncounter {
		public String date;
		public String reason;
		public String facility;
		/**
		 * ID for the encounter
		 */
		public int eid;
		/**
		 * Unique ID for the encounter, required later to add e.g. a patient measurement
		 */
		public String euuid;
		
		@Override
		public String toString() {
			StringBuilder sb=new StringBuilder(date).append(" ");
			if(reason.length()>10) {
				sb.append(reason.substring(0,10));
			} else {
				sb.append(reason);
			}
			return sb.toString();
		}
	}

	public PatientEncounter addNewEncounter(String uuid, String date, String reason) throws Exception {
		HttpClient httpClient=getHttpClient();
		JsonObjectBuilder objectBuilder=Json.createObjectBuilder();
		JsonObject json=objectBuilder.add("date", date)
		.add("reason", reason)
		.add("pc_catid","9")	//Hard coded value from OpenEMR gui - established patient
		.add("class_code", "FLD")	//Hard coded value from OpenEMR gui - "Out In Field"
		.build();
		System.err.println("new encounter body is "+json.toString());
		HttpRequest request=HttpRequest.newBuilder().uri(
			new URI("https://"+openEMRURL+"/apis/"+scope+"/api/patient/"+uuid+"/encounter")
		).header("Authorization", "Bearer "+accessToken)
		.header("Content-Type","application/json")
		.POST(BodyPublishers.ofString( json.toString() ))
		.build();
		HttpResponse<byte[]> handler=httpClient.send(request, BodyHandlers.ofByteArray());
		System.err.println("new encounter status code is "+handler.statusCode());
		if(handler.statusCode()>201) {
			System.err.println("Failed to post new encounter request");
			System.err.println("response body is "+new String(handler.body()));
			throw new Exception("Failed to post new encounter request");
		}
		ByteArrayInputStream bais=new ByteArrayInputStream(handler.body());
		JsonReader reader=Json.createReader(bais);
		JsonObject resultsRoot=reader.readObject();
		JsonObject newEncounterData=(JsonObject)resultsRoot.get("data");
		System.err.println("newEncounterData is "+newEncounterData.toString());
		PatientEncounter newEncounter=new PatientEncounter();
		newEncounter.date=date;
		newEncounter.reason=reason;
		try {
			//Don't know why it's called encounter in the return here, but eid in the GET.
			newEncounter.eid=newEncounterData.getInt("encounter"); 
		} catch (NullPointerException npe) {
			System.err.println("No encounter info in response to new encounter - setting to -1");
			newEncounter.eid=-1;
		}
		//Ditto, this is euuid when doing a GET, but uuid here...
		newEncounter.euuid=newEncounterData.getString("uuid");
		return newEncounter;
	}
	
	/**
	 * Add a new vital sign to a patient in OpenEMR.
	 * 
	 * Vitals are e.g. bps for systolic BP, bpd for diastolic BP, oxygen_saturation for SpO2.
	 * See the OpenEMR docs for the full list
	 *  
	 * @param patientId The Patient ID (NOT the UUID)
	 * @param encounterId The encounter ID (NOT the EUUID)
	 * @param values a Map of key/value pairs, such as oxygen_saturation, 98
	 * @throws Exception 
	 */
	public void addVitalSign(String patientId, String encounterId, HashMap<String,String> values) throws Exception {
		HttpClient httpClient=getHttpClient();
		JsonObjectBuilder objectBuilder=Json.createObjectBuilder();
		values.forEach( (param,value) -> {
			objectBuilder.add(param, value);
		});
		JsonObject json=objectBuilder.build();
		System.err.println("json for new metric object is "+json.toString());
		HttpRequest request=HttpRequest.newBuilder().uri(
				new URI("https://"+openEMRURL+"/apis/"+scope+"/api/patient/"+patientId+"/encounter/"+encounterId+"/vital")
			).header("Authorization", "Bearer "+accessToken)
			.header("Content-Type","application/json")
			.POST(BodyPublishers.ofString( json.toString() ))
			.build();
		HttpResponse<byte[]> handler=httpClient.send(request, BodyHandlers.ofByteArray());
		if(handler.statusCode()>201) {
			System.err.println("Failed to post new vital request");
			System.err.println("response body is "+new String(handler.body()));
			throw new Exception("Failed to post new vital request");
		}
		ByteArrayInputStream bais=new ByteArrayInputStream(handler.body());
		JsonReader reader=Json.createReader(bais);
		JsonObject resultsRoot=reader.readObject();
		//JsonObject newVitalData=(JsonObject)resultsRoot.get("data");
		System.err.println("newVitalData is "+resultsRoot.toString());
		
	}
	
	/**
	 * Return a list of orders from the OpenEMR server
	 */
	public ArrayList<String> getCurrentOrders() throws Exception {
		HttpClient httpClient=getHttpClient();
		HttpRequest request=HttpRequest.newBuilder().uri(
				new URI("https://"+openEMRURL+"/b-labs/results/")
			).header("Authorization", "Bearer "+accessToken)
			.GET().build();
		HttpResponse<String> handler=httpClient.send(request, BodyHandlers.ofString());
		String fullResponse=handler.body();
		System.err.println("response is "+fullResponse);
		String[] lines=fullResponse.split("[\r\n]");	//USe character class to split on either line terminator
		ArrayList<String> returnList=new ArrayList<>();
		for(String line : lines) {
			//This is quite crude but will do for now. Later, take the prefix.
			if(line.indexOf("MDPNP-") != -1) {
				String[] parts=line.split("\"");
				returnList.add(parts[1]);
			}
		}
		return returnList;
	}
	
	public String getOrderContents(String filename) throws Exception {
		HttpClient httpClient=getHttpClient();
		HttpRequest request=HttpRequest.newBuilder().uri(
				new URI("https://"+openEMRURL+"/b-labs/results/"+filename)
			).header("Authorization", "Bearer "+accessToken)
			.GET().build();
		HttpResponse<String> handler=httpClient.send(request, BodyHandlers.ofString());
		String response=handler.body();
		return response;
	}
}
