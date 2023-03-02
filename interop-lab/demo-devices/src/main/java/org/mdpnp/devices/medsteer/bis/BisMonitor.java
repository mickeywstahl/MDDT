package org.mdpnp.devices.medsteer.bis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalField;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.ArrayUtils;
import org.mdpnp.devices.nihon.koden.NKV550;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

public class BisMonitor extends AbstractSerialDevice {
	
	private static final Logger log = LoggerFactory.getLogger(BisMonitor.class);
	
	//Could we do enum here in a java style?
	private static final int REQ_REV=0;
	private static final int REQ_ERRORS=1;
	private static final int REQ_IMPEDS=2;
	private static final int REQ_EVT=3;
	private static final int REQ_VAR_LABELS=4;
	private static final int REQ_VARS=5;
	private static final int REQ_VARS_WITH_SPECTRA=6;
	private static final int REQ_RAW=7;
	private static final int REQ_NO_ERRORS=8;
	private static final int REQ_NO_IMPEDS=9;
	private static final int REQ_NO_EVT=10;
	private static final int REQ_NO_VARS=11;
	private static final int REQ_NO_RAW=12;
	
//	private static final int[] connect_sequence=new int[] {REQ_REV, REQ_VAR_LABELS, REQ_ERRORS, REQ_EVT, REQ_VARS};
	private static final int[] connect_sequence=new int[] {REQ_REV};
	
	private static final int PAYLOAD_SIZE_MAX=0x800;
	
	private boolean binary;	//binary=true, ascii=false;
	
	private static final DateTimeFormatter formatter=new DateTimeFormatterBuilder().parseCaseInsensitive()
			.appendValue(ChronoField.MONTH_OF_YEAR,2)
			.appendLiteral('/')
			.appendValue(ChronoField.DAY_OF_MONTH,2)
			.appendLiteral('/')
			.appendValue(ChronoField.YEAR,4)
			.appendLiteral(' ')
			.appendValue(ChronoField.HOUR_OF_DAY,2)
			.appendLiteral(':')
			.appendValue(ChronoField.MINUTE_OF_HOUR,2)
			.appendLiteral(':')
			.appendValue(ChronoField.SECOND_OF_MINUTE)
			.toFormatter();
	
	/**
	 * Highest possible message id for replies from the device.
	 */
	private static final int MAX_REPL_ID=1115;
	
	private short m_seq_num[]=new short[MAX_REPL_ID];
	
	private short m_seq_id_host;
	
	private int m_acked=-1;
	
	private static final short L1_DATA_PACKET=1;
	
	private static final short L1_ACK_PACKET=2;
	
	private static final short L1_NAK_PACKET=3;
	
	private static final short START_OF_PACKET=(short)0xabba;	//Can this work safely?
	
	private static final int M_DATA_RAW =     50;
	private static final int M_PROCESSED_VARS =       52;
	private static final int M_PROCESSED_VARS_AND_SPECTRA =    53;

	private static final int SER_IMPED_MSG =  1100;
	private static final int SER_ERROR_MSG =  1101;
	private static final int SER_REVISION_INFO =      1102;

	private static final int M_PROCESSED_VARS_LABELS = 1103;

	private static final int SER_EEG_SNIPPET_HEADER = 1104;
	private static final int SER_EEG_SNIPPET_RAW_DATA =       1105;
	private static final int SER_EEG_SNIPPET_PROCESSED_DATA = 1106;
	private static final int SER_NO_SNIPPET = 1107;
	private static final int SER_SNIPPET_CORRUPTED =  1108;

	private static final int SER_BIS_HISTORY_HEADER = 1109;
	private static final int SER_BIS_HISTORY_DATA  =  1110;
	private static final int SER_NO_HISTORY        =  1111;

	private static final int SER_EVENT_MSG         =  1115;

	
	private static final String[] request_name=new String[] {
		"Revision info",
        "Error messages",
        "Impedance messages",
        "Event messages",
        "Labels",
        "Processed variables",
        "Processed variables with spectra",
        "Raw EEG",
        "Turn off errors",
        "Turn off impedance messages",
        "Turn off events",
        "Turn off processed variables",
        "Turn off raw EEG"
	};
	
	private int m_total_pkt_count;
	
	//private byte[] m_rcv_buff=new byte[0x8000];
	/**
	 * The receive buffer
	 */
	ByteBuffer m_rcv_buff=ByteBuffer.allocate(0x8000);
	
	/**
	 * A byte buffer for leftover bytes that come from packets with e.g. short length.
	 * Unsure of the allocate size here...
	 */
	ByteBuffer m_leftover=ByteBuffer.allocate(0x8000);
	
	int[] m_channel_field=new int[3];
	
	private Vector<Vector<String>> wanted_fields;
	
	private HashMap<Integer, String> response_name=new HashMap<>();
	
	private HashMap<String, Integer> m_bis_field_num=new HashMap<>();
	
	/**
	 * a packet header.  watch out for the usual problems with the fact that these are unsigned in the c++ code
	 * We'll overcome that by treating is as 8 bytes. (4 two byte quantities)
	 * @author MDPNP
	 *
	 */
	class bis_a2k_l1_hdr {
		
		private ByteBuffer bb;
		
		bis_a2k_l1_hdr() {
			bb=ByteBuffer.allocate(8);
			bb.putShort((short)0xabba);
		}
		
		void setSeqId(short seqId) {
			if(bb.position()!=2) {
				bb.position(2);
			}
			bb.putShort(seqId);
		}
		
		void setPayloadSize(short payloadSize) {
			if(bb.position()!=4) {
				bb.position(4);
			}
			bb.putShort(payloadSize);
		}
		
		void setDirective(short directive) {
			if(bb.position()!=6) {
				bb.position(6);
			}
			bb.putShort(directive);
		}
		
		short getStartOfPacket() {
			return bb.getShort(0);
		}
		
		short getPayloadSize() {
			return bb.getShort(4);
		}
		
		short getDirective() {
			return bb.getShort(6);
		}
		
		/*
		void setOptionalData(byte[] optional) {
			if(bb.position()!=8) {
				throw new IllegalArgumentException("setSeqId must be called last");
			}
			bb.put(optional);
		}
		*/
		
		/*
		 * does the checksum belong here or not - seems to be a difference between the docs and c code
		 */
		void checksum() {
			short sum=0;
			for(int i=0;i<bb.position();i++) {
				sum+=bb.get(i);
			}
			bb.putShort(sum);
		}
		
		byte[] getBytes() {
			return bb.array();
		}
		
	}
	
	private static final int L1_HDR_SIZE=8;
	
	class bis_a2k_payload_hdr {
		private ByteBuffer bb;
		
		bis_a2k_payload_hdr() {
			bb=ByteBuffer.allocate(12);	//int, int, short, short
		}
		
		void setRoutingIdentifier(int routingID) {
			if(bb.position()>0) {
				bb.position(0);
			}
			bb.putInt(routingID);
		}
		
		void setMessageId(int messageID) {
			if(bb.position()>4) {
				bb.position(4);
			}
			bb.putInt(messageID);
		}
		
		void setSeqNum(short seq_num) {
			if(bb.position()>8) {
				bb.position(8);
			}
			bb.putShort(seq_num);
		}
		
		void setLength(short length) {
			if(bb.position()>10) {
				bb.position(10);
			}
			bb.putShort(length);
		}
		
		int getLength() {
			return bb.getShort(10);
		}
		
		int getMessageId() {
			return bb.getInt(4);
		}
		
		byte[] getBytes() {
			return bb.array();
		}
		
		/*
		void putAppData(byte appData) {
			bb.put(appData);
		}
		*/
	}
	
	private static final int PAYLOAD_HDR_SIZE=12;
	
	class revision_info_msg {
		byte[] system_revision=new byte[4];
		byte[] host_revision=new byte[4];
		byte[] bis_eng_revision=new byte[4];
		byte[] fpga_revision=new byte[4];
		byte[] boot_revision=new byte[4];
		byte[] hardware_revision=new byte[4];
		byte[] serial_number=new byte[4];
		@Override
		public boolean equals(Object obj) {
			if(! (obj instanceof revision_info_msg) ) {
				//Obviously not!
				return false;
			}
			revision_info_msg other=(revision_info_msg)obj;
			// TODO Auto-generated method stub
			for(int i=0;i<4;i++) {
				if(system_revision[i]!=other.system_revision[i]) {
					return false;
				}
				if(bis_eng_revision[i]!=other.bis_eng_revision[i]) {
					return false;
				}
				if(bis_eng_revision[i]!=other.bis_eng_revision[i]) {
					return false;
				}
				if(fpga_revision[i]!=other.fpga_revision[i]) {
					return false;
				}
				if(boot_revision[i]!=other.boot_revision[i]) {
					return false;
				}
				if(hardware_revision[i]!=other.hardware_revision[i]) {
					return false;
				}
				if(serial_number[i]!=other.serial_number[i]) {
					return false;
				}
			}
			return true;
		}
		
		
	}
	
	private revision_info_msg temp_revision_info, m_revision_info;
	
	class snippet_trend_variables_info {
		short impedance_value;
		short burst_suppress_ratio;
        short bis_bits;
        short bispectral_index;
        short bispectral_alternate_index;
        short bispectral_alternate2_index;
        short emg_low;
        short bis_signal_quality;
        int second_artifact;
	}
	
	class dsc_info_struct {
		byte dsc_id;
		byte dsc_id_legal;
		byte pic_id;
		byte pic_id_legal;
		short dsc_numofchan;
		short quick_test_result;
	}
	
	class impedance_info_struct {
		short impedance_value;
		short imped_test_result;
	}
	
	class be_trend_variables_info {
		short burst_suppress_ratio;
		short spectral_edge_95;
		short bis_bits;
		short bispectral_index;
		short bispectral_alternate_index;
		short bispectral_alternate2_index;
		short total_power;
		short emg_low;
		int bis_siqnal_quality;
		int second_artifact;
	}
	
	class processed_vars_msg {
		dsc_info_struct proc_dsc_info;
		impedance_info_struct[] impedance_info=new impedance_info_struct[2];
		int host_filt_settings;
		int host_smoothing_settings;
		int host_spectral_art_mask;
		int host_bispectral_art_mask;
		be_trend_variables_info[] trend_variables=new be_trend_variables_info[3];
		
		@Override
		public String toString() {
			return "host_filt_settings:" + host_filt_settings+" host_smoothing_settings: "+host_smoothing_settings+" host_spectral_art_mask: "+host_spectral_art_mask+" host_bispectral_art_mask: "+host_bispectral_art_mask;
		}
		
		processed_vars_msg() {
			for(int i=0;i<3;i++) {
				trend_variables[i]=new be_trend_variables_info();
			}
		}
	}
	
	private processed_vars_msg m_data;
	
	private StringBuilder m_converter=new StringBuilder();
	
	private boolean initDone;
	
	private BufferedInputStream fromDevice;
	private BufferedOutputStream toDevice;

	public BisMonitor(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,1);
	}

	public BisMonitor(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
        writeDeviceIdentity();
		init_response_names();
		init_wanted_fields();
	}

	@Override
	protected void doInitCommands(int idx) throws IOException {
		initDone=false;		//in case we are coming back here after e.g. comms fail.
		if(binary) {
			initialize_binary();
		} else {
			initialize_ascii();
		}
		
		initDone=true;
	}
	
	private void initialize_ascii() throws IOException {
		log.info("Doing ASCII initialization");
		send( new byte[] {'C'}, 1 );
		try {
			Thread.sleep(100);
		} catch (InterruptedException ie) {
			ie.printStackTrace();
		}
		//Don't think we can do a flush of the input stream.  Perhaps if we go lower level I/O?
		//tcflush(m_fd, TCIFLUSH); // Discard unread data
		long timeout=500;
		log.info("Requesting version information");
		send( new byte[] {'V'}, 1 );	//Wrap and rethrow in c++ code not necessary here.
		get_response("version", true, timeout);
		
		log.info("requesting variables");
		send( new byte[] {'U'}, 1 );
		m_channel_field[0]=m_channel_field[1]=m_channel_field[2]=0;
		m_bis_field_num.clear();
		get_response("headers (1)", true, timeout);
		get_response("headers (2)", true, timeout);
		reportConnected("Retrieved info from device");
	}
	
	private void get_response(String msg, boolean throw_error, final long timeout) throws IOException {
		int prev_pkt_count=m_total_pkt_count;
		long t0=System.currentTimeMillis();
		//This seems a pretty narrow window! 
		while(System.currentTimeMillis()-t0 <= timeout) {
			long ts=recv(timeout);
			if(m_rcv_buff.position()>0) {
				parse_packets(ts);
			}
			if(!throw_error || m_total_pkt_count > prev_pkt_count) {
				break;
			}
		}
		if(throw_error && m_total_pkt_count == prev_pkt_count) {
			//We should throw an error because we didn't get any new packets.
			log.error("NO DATA");
			throw new IOException(msg); 
		}
	}
	
	private void parse_packets(long ts) {
		/*
		 * The C++ code does pointer arithmetic here.  We will just use indexes that track the equivalent
		 * position of the pointer.  The pkt pointer gets set to m_rcv_buff[0] - so we just have pkt=0
		 * to start with.  end gets set to the end of the packet, so we just reference the buff position for that.
		 */
		int pkt=0;
		int end=m_rcv_buff.position();
		
		try {
			while(pkt<end) {
				byte bytes[]=new byte[end-pkt];
				System.arraycopy(m_rcv_buff.array(), pkt, bytes, 0, end-pkt);
				int size=binary ? parse_one_packet(pkt, end) : parse_ascii_record(bytes,end, ts);
				pkt+=size;
			}
		} catch (IOException ioe) {
			System.err.println("NEED TO IMPLEMENT SHORT PACKET HANDLING!!!!!");
			log.error("NEED TO IMPLEMENT SHORT PACKET HANDLING!!!!!");
			//m_leftover.put(0)
		}
	}
	
	
	private int parse_ascii_record(byte bytes[],int end, long ts) throws IOException {
		
		System.err.println("parse_ascii_record bytes are");
		System.err.println(ArrayUtils.toString(bytes));
		System.err.println("parse_ascii_record String is");
		System.err.println(new String(bytes));
		int start=0;
		while(bytes[start]==0x00) {
			//Maybe we NEED a statement to here to ensure this doesn't get skipped? It shouldn't do though, as get() is an op with side effects.
			start++;
			System.err.println("Skipped a byte");
			log.debug("Skipped a byte");
		}
//		int eor=m_rcv_buff.position();
//		int next=m_rcv_buff.position();
		int eor=0;
		int next=0;
		
		while(eor<end) {
			if(bytes[eor]==0x0d) {
				break;
			}
			++eor;
		}
		System.err.println("eor is "+eor+" and end is "+end);
		if(eor>end) {
			throw new IOException("Short packet "+(eor-m_rcv_buff.position()));
		}
		
		next=eor;
		++next;
		if(next<end && bytes[next]==0x0a) {
			++next;
		} else {
			throw new IOException("missing LF");
		}
		if(next<end && bytes[next]==0x00) {
			++next;
		}
		Object result=null;
		int ret=parse_bis_a2k_record(bytes,start,eor,result);
		
		handle_packet(result, ret, ts);
		m_total_pkt_count+=1;
		
		return next;
		
		
		//parse_bis_a2k_packet(pkt_content, next, end, connect_sequence)
	}
	
	/**
	 * Parse the incoming bytes into pkt_content.  This is used for an ASCII packet.
	 * In the C++ code, pkt_content is pkt, which is a union bis_a2k_pkt_ptr.  We can't
	 * do a union in Java so we just treat it as an Object.  We then set Object to be an
	 * instance of whatever class is appropriate for the input bytes (That are converted
	 * to be a String).<br/>
	 * <br/>
	 * So although the return value is an int representing the record type, the result
	 * object is used by the caller for further processing.  In some cases where we are
	 * just setting class level member variables directly, the return Object may be null.
	 * Those correspond to cases where the return value is commented with Fake</br>
	 * <br/>
	 * bytes is the incoming data.  In the C++ code that's a pointer to an array, and the
	 * pointer is incremented until leading null bytes have been skipped. We can't do that
	 * in Java either, so we just take skip as an int for how many bytes to skip. 
	 * <br/>
	 * @param bytes the bytes to parse
	 * @param skip the start position in bytes
	 * @param end the end of the packet
	 * @param result the object to put the result in.
	 * @return an integer representing the record type identified from bytes
	 * @throws IOException 
	 */
	private int parse_bis_a2k_record(byte[] bytes, int skip, int end, Object result) throws IOException {
		String line;
		if(skip>0) {
			//We need to trim some off.
			System.err.println("Skip is "+skip+" - need to allocate "+(end-skip+2));
			byte[] trimmed=new byte[end-skip+2];	//Allow for \r\n
			System.arraycopy(bytes, skip, trimmed, 0, end-skip+2);
			line=new String(trimmed);
		} else {
			//THINK this is OK.
			line=new String(bytes);
		}
		//Split appears to be using | symbol.  Must escape that as it's a special char in split in Java
		String[] fields=line.split("\\|");
		if(fields.length==0) {
			log.error("%s had no fields",line);
			throw new IOException("line had no fields");
		}
		
		if(fields[0].trim().equals("VERSION") ) {	//We use trim here as it has a trailing space when split on |
			log.debug("Got VERSION record");
			temp_revision_info=new revision_info_msg();
			//This all seems a bit hardcore for a thing that is already a string.
			String system_revision=fields[2].trim();
			byte sys_rev_bytes[]=system_revision.getBytes();
			temp_revision_info.system_revision[0]=sys_rev_bytes[0];	//Not sure what the -'0' means in C++ code 
			temp_revision_info.system_revision[1]=sys_rev_bytes[2];
			temp_revision_info.system_revision[2]=sys_rev_bytes[3];
			//host sw revision
			temp_revision_info.host_revision[0]=sys_rev_bytes[0];	//Not sure what the -'0' means in C++ code 
			temp_revision_info.host_revision[1]=sys_rev_bytes[2];
			temp_revision_info.host_revision[2]=sys_rev_bytes[3];
			//Bis engine revision
			temp_revision_info.bis_eng_revision[0]=sys_rev_bytes[0];	//Not sure what the -'0' means in C++ code 
			temp_revision_info.bis_eng_revision[1]=sys_rev_bytes[2];
			temp_revision_info.bis_eng_revision[2]=sys_rev_bytes[3];
			//hardware revision
			temp_revision_info.hardware_revision[0]=sys_rev_bytes[0];	//Not sure what the -'0' means in C++ code 
			temp_revision_info.hardware_revision[1]=sys_rev_bytes[2];
			temp_revision_info.hardware_revision[2]=sys_rev_bytes[3];
			//Serial number
			String numOnly=fields[8].trim().substring(1);
			int sn=Integer.parseInt(numOnly);
			temp_revision_info.serial_number[0]=(byte)(sn & 0xff);
			temp_revision_info.serial_number[1]=(byte) ((sn & 0xff00) >> 8);
			temp_revision_info.serial_number[2]=(byte) ((sn & 0xff0000) >> 16);
			temp_revision_info.serial_number[2]=(byte) fields[8].trim().getBytes()[0];
			result=temp_revision_info;
			return SER_REVISION_INFO;
		} else if(fields[0].trim().equals("S_HDR3")) {
			short channelCount=0;
			for(int i=0;i<fields.length;i++) {
				if(fields[i].trim().equals("Ch. 1")) {
					m_channel_field[0]=i;
					channelCount++;
				}
				if(fields[i].trim().equals("Ch. 2")) {
					m_channel_field[1]=i;
					channelCount++;
				}
				if(fields[i].trim().equals("Ch. 12")) {
					m_channel_field[2]=i;
					channelCount++;
				}
			}
			if(channelCount!=2) {
				log.error("Incorrect channel count %d",channelCount);
				//Should this throw?
			}
			result=null;
			return L1_ACK_PACKET;	//Fake.
		} else if (fields[0].trim().equals("TIME")) {
			int foundCount=0;
			/*
			 * First, loop through all the wanted fields.  Each of these has a regexp first,
			 * followed by a field name.  We want to match the field with the regexp, and if
			 * it matches, set the field name to have the index of that field.
			 */
			for(Iterator<Vector<String>> iter=wanted_fields.iterator();iter.hasNext();) {
				Vector<String> pair=iter.next();
				String regexp=pair.get(0);
				String fieldName=pair.get(1);
				Pattern p=Pattern.compile(regexp);
				/*
				 * Now we have the pattern from the regexp, check it against all the fields.
				 */
				for(int i=1;i<fields.length;i++) {
					System.err.println("Checking "+regexp+" against "+fields[i].trim());
					Matcher m=p.matcher(fields[i].trim());
					if(m.matches()) {
						System.err.println("that is a match.  Setting "+fieldName+" in map");
						m_bis_field_num.put(fieldName, i);
						foundCount++;
					}
				}
			}
			return L1_ACK_PACKET;	//Fake
		} else if(is_record_date(fields[0].trim())) {
			processed_vars_msg processed_vars=new processed_vars_msg();
			for(int ch=0; ch<3; ++ch) {
				int index=m_channel_field[ch];
				be_trend_variables_info info=processed_vars.trend_variables[ch];
				
				double temp=0;
				m_converter.setLength(0);	//Clear
				int srNum=m_bis_field_num.get("SR");
				System.err.println("srNum is "+srNum);
				String srStr=fields[srNum];
				System.err.println("srStr is "+srStr);
				m_converter.append(srStr);
				temp=Double.parseDouble(m_converter.toString());
				System.err.println("temp is "+temp);
				info.burst_suppress_ratio=(short)(10*temp);
				
				m_converter.setLength(0);	//Clear
				m_converter.append(fields[m_bis_field_num.get("BIS")]);
				temp=Double.parseDouble(m_converter.toString());
				info.bispectral_index=(short)(10*temp);
				
				m_converter.setLength(0);	//Clear
				m_converter.append(fields[m_bis_field_num.get("EMGLOW")]);
				temp=Double.parseDouble(m_converter.toString());
				info.emg_low=(short)(100*temp);
				
				m_converter.setLength(0);	//Clear
				m_converter.append(fields[m_bis_field_num.get("SQI")]);
				temp=Double.parseDouble(m_converter.toString());
				info.bis_siqnal_quality=(int)(10*temp);
				
			}
			result=processed_vars;
			return M_PROCESSED_VARS;
		} else {
			log.error("unknown record %s",fields[0]);
			return L1_NAK_PACKET;
		}
	}
	
	private boolean is_record_date(final String str) {
		//9/16/2022 13:39:50
		System.err.println("is_record_date checking "+str);
		char chars[]=str.toCharArray();
		boolean b=str.length()==19 && chars[2]=='/' && chars[5]=='/' &&
				chars[10]==' ' && chars[13]==':' && chars[16]==':';
		if(b) {
			System.err.println("Yes, that is a record date");
		}
		return b;
	}

	private void handle_packet(Object packet, int type, long ts) throws IOException {
		String isthere = response_name.get(type);
        String tname = (isthere!=null  ? isthere : "<unknown>");

        System.err.println("packet type "+tname);

        // actual handling
        switch (type) {
        case L1_ACK_PACKET:
                log.debug("ACK received");
                m_acked = 1;
                break;
        case L1_NAK_PACKET:
                log.error("NAK received");
                m_acked = 0;
                break;
        case SER_BIS_HISTORY_HEADER:
//                m_history_lock.lock();
//                m_history_header = *(pkt.history_info);
//                m_history_lock.unlock();
                break;
        case SER_BIS_HISTORY_DATA:
                //add_history_records(pkt.history_data);
                break;
        case SER_NO_HISTORY:
                log.debug("no more history");
//                m_history_lock.lock();
//                m_history_header.num_records = 0;
//                m_history_lock.unlock();
                break;
        case M_PROCESSED_VARS:
                set_vars(ts, (processed_vars_msg)packet);
                break;
        case M_PROCESSED_VARS_AND_SPECTRA:
//                set_vars(ts, pkt.processed_vars_and_spectra->processed_vars);
                break;
        case SER_REVISION_INFO:
                set_revision_info((revision_info_msg)packet);
                break;
        case M_PROCESSED_VARS_LABELS:
//                set_labels(pkt.processed_vars_labels);
                break;
        case SER_IMPED_MSG:
        case SER_ERROR_MSG:
        case SER_EVENT_MSG:
                //add_message(ts, type, pkt.string);
                break;
        default:
                log.error("not handling packet type %d (%s)", type, tname);
        }
		
	}

	/**
	 * A version of BisA2k::send
	 * 
	 * @param bytes The bytes to send
	 * @param len The length of data to send.
	 * 
	 * The C++ code takes a void pointer for msg, hence the need for len.
	 * As we can't take a pointer, we take bytes instead, so we probably don't need
	 * the len parameter as it's almost always going to be msg.length.  But we'll take
	 * it anyway for when we need it. 
	 * @throws IOException 
	 */
	private void send(byte[] msg, int len) throws IOException {
		toDevice.write(msg, 0, len);
		toDevice.flush();
	}
	
	/**
	 * A version of BisA2K::recv
	 * 
	 * @param timeout the timeout, effectively in milliseconds.
	 * @return the time in milliseconds when the first data was received.
	 * @throws IOException
	 */
	private long recv(long timeout) throws IOException {
		m_rcv_buff.position(0);	//Reset.
		
		int count=0;
		byte[] c=new byte[1];	//It's a char in the C++ code.	Using size 1 here is possibly inefficient but we are reading from a buffered stream underneath.
		long t0=System.currentTimeMillis();
		boolean timed_out=false;
		boolean first=false;
		long ts=0;
		do {
			count=fromDevice.read(c);
			if(count > 0 && c[0]!=0x00) {
				//This looks very odd.  first starts off as false, and then if !first (which will be true) then set first=false in the block.  Which guarantees that first is always false and this always runs.
				if(!first) {
					ts=System.currentTimeMillis();
					//first=false;	I'm swapping this to true.
					first=true;
				}
				m_rcv_buff.put(c);
			} else if (count == -1) {
				//int err=errno; and then nothing in the C++;
			}
			timed_out = (System.currentTimeMillis() - t0) > timeout;
		} while( c[0]!=0x0a && !timed_out);
		System.err.println("Final c[0] is "+c[0]+", timed_out is "+timed_out);
		log.debug("Received %d bytes in total",m_rcv_buff.position());
		return ts;
	}
	
	
	//c++ driver has choice of protocol.  This is based on initialize_binary
	private void initialize_binary() throws IOException {
		int num_resend=0;
		int curr_req=0;
		while(curr_req<connect_sequence.length) {
			if(num_resend>0) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException ie) {
					//oh.
				}
			}
			sendRequest(connect_sequence[curr_req]);
			//getResponse(request_name[curr_req], false, 2);
			curr_req++;
		}
	}
	
	private void sendRequest(int request) throws IOException {
		switch (request) {
        case REQ_REV:
                send_revision_info();
                break;
        case REQ_ERRORS:
                turn_on_error_msg();
                break;
        case REQ_IMPEDS:
                turn_on_imped_msg();
                break;
        case REQ_EVT:
                turn_on_send_event();
                break;
        case REQ_VAR_LABELS:
                send_processed_vars_labels();
                break;
        case REQ_VARS:
                send_processed_vars(0);
                break;
        case REQ_VARS_WITH_SPECTRA:
                send_processed_vars(1);
                break;
        case REQ_NO_VARS:
                stop_processed_vars();
                break;
        case REQ_NO_ERRORS:
                turn_off_error_msg();
                break;
        case REQ_NO_EVT:
                turn_off_send_event();
                break;
        case REQ_NO_RAW:
                stop_raw_eeg();
                break;
        default:
                throw new RuntimeException("BisA2K::send_request wrong request\n");
		}

	}
	
//	/**
//	 * This needs some more work on timing, error handling and so on, but
//	 * the point is just to read a line from the socket.
//	 * @param msg
//	 * @param throwError
//	 * @param timeout
//	 * @throws IOException
//	 */
//	private void getResponse(String msg, boolean throwError, long timeout) throws IOException {
//		try {
//			System.err.println("getResponse called for "+msg);
//			int prev_pkt_count=m_total_pkt_count;
//			byte b=0x00;
//			int pos=0;
//			do {
//				b=(byte) (fromDevice.read() & 0xff);
//				m_rcv_buff[pos++]=b;
//				if( pos % 10 == 0 ) {
//					System.err.println("Got "+pos+" bytes");
//				}
//				if(pos==100) {
//					byte debugBytes[]=new byte[100];
//					System.arraycopy(m_rcv_buff, 0, debugBytes, 0, 100);
//					System.err.println("getResponse first hundred bytes "+ArrayUtils.toString(debugBytes));
//				}
//			} while (b!=0x0a && b!=-1 && pos<m_rcv_buff.length);
//			System.err.println("After do loop, pos is "+pos+" and b is "+b);
//			if(pos>0) {
//				//We got data
//				parse_packets(pos);	//We pass the pos to parse_packets as endPos so it knows how many bytes to pass
//			}
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//	}
	
//	private void parse_packets(int endPos) throws IOException {
//		int startPos=0;		//This points to the start of m_rcv_buff in the C code.
//		/*
//		 * In the C++ code, it uses m_rcv_buff.size() to know the end point - but we are just using an array,
//		 * so we can't call something like that to know how many bytes are in it.  Fortunately in the caller
//		 * (getResponse in this case), it tracked the number of bytes read, so we just use endPos for that,
//		 * instead of the pointer arithmetic style in the C++ code.
//		 */
//		while(startPos<endPos) {
//			startPos+=parse_one_packet(startPos, endPos);
//		}
//		
//	}
	
	private int parse_one_packet(int start, int end) throws IOException {
		//The C/C++ code uses a union, which is not possible in Java.
		byte[] pkt_content=new byte[PAYLOAD_SIZE_MAX];
		
		int next=start;
		int skipped=0;
		byte b1,b2;
		while(next<end) {
			if( m_rcv_buff.getShort()==0xbaab) {	//This might be supposed to be 0xabba - not sure re byte ordering yet.
				next+=2;	//Check this as well, if we ever need to come back to binary mode.
				break;
			}
			++skipped;
			++next;
		}
		
		/*
		 * In the C++ code, pkt is a union with void *base as the first member,
		 * and base is set to point to pkt_content.  We can't do a union here,
		 * and all that we really need is to pass pkt_content into parse_bis_a2k_packet
		 * so that that method can populate pkt_content.
		 */
		int ret=parse_bis_a2k_packet(pkt_content,next,end, new int[] {next});
		handle_packet(pkt_content, ret);
		
		return next-start;
	}
	
	//We are using the contents of m_rcv_buff here.
	int parse_bis_a2k_packet(byte[] pkt, int next, int end, int[] nextRef) throws IOException {
		
		short checksum=0;	//Our calculated checksum
		short pktsum;		//Checksum from the packet in m_rcv_buff
		int checkptr=next;
		int ptr=next;
		
		//First bytes in the packet should be l1 header
		bis_a2k_l1_hdr l1=new bis_a2k_l1_hdr();
		
		bis_a2k_payload_hdr hdr=new bis_a2k_payload_hdr();
		//l1.setSeqId();
		//We are hammering the use of byte buffers here, but we can optimise later.
		//Again, they aid debugging.
		l1.setSeqId(m_rcv_buff.getShort());
		l1.setPayloadSize(m_rcv_buff.getShort());
		l1.setDirective(m_rcv_buff.getShort());
		
		//Important to now use bb for processing to keep position of bb in sync.
		//Do not mix and match with m_rcv_buff[ptr]
		
		checkptr+=2;	//This omits the 0xabba header bytes. (or perhaps 0xbaab on a  received packet)
		while( checkptr < next+L1_HDR_SIZE ) {
			checksum+=m_rcv_buff.get();
			++checkptr;
		}
		
		ptr+=L1_HDR_SIZE;
		if(l1.getStartOfPacket()!=START_OF_PACKET) {
			System.err.println("l1.startOfPacket was "+l1.getStartOfPacket());
			throw new IOException("Incorrect packet header");
		}
		
		if(l1.getDirective()==L1_DATA_PACKET) {
			hdr.setRoutingIdentifier(m_rcv_buff.getInt());
			hdr.setMessageId(m_rcv_buff.getInt());
			hdr.setSeqNum(m_rcv_buff.getShort());
			hdr.setLength(m_rcv_buff.getShort());
			
			while(checkptr<ptr+PAYLOAD_HDR_SIZE) {
				checksum+=m_rcv_buff.get();
				++checkptr;
			}
			
			if( l1.getPayloadSize() != hdr.getLength() + PAYLOAD_HDR_SIZE) {
				System.err.println("l1.payloadSize was "+l1.getPayloadSize()+" - expected " + (hdr.getLength()+ PAYLOAD_HDR_SIZE) );
				throw new IOException("L1/L2 size mismatch");
			}
			
			switch(hdr.getMessageId()) {
			case M_DATA_RAW:
            case M_PROCESSED_VARS:
            case M_PROCESSED_VARS_AND_SPECTRA:
            case M_PROCESSED_VARS_LABELS:
            case SER_IMPED_MSG:
            case SER_ERROR_MSG:
            case SER_REVISION_INFO:
            case SER_EEG_SNIPPET_HEADER:
            case SER_EEG_SNIPPET_RAW_DATA:
            case SER_EEG_SNIPPET_PROCESSED_DATA:
            case SER_NO_SNIPPET:
            case SER_SNIPPET_CORRUPTED:
            case SER_BIS_HISTORY_HEADER:
            case SER_BIS_HISTORY_DATA:
            case SER_NO_HISTORY:
            case SER_EVENT_MSG: 
            	/*
            	 * In the C++ code, pkt is a union, with a "base" that contains the data.  We can't do a union in Java,
            	 * so we'll just treat it as bytes.  In all these cases the C++ code just copies ptr into pkt, using hdr.length
            	 * as the count of what to do.  Because we wrapped m_rcv_buff in bb, we can use bb position to know what the offset
            	 * in m_rcv_buff is
            	 */
            	byte[] sourceBytes=m_rcv_buff.array();
            	System.arraycopy(sourceBytes,m_rcv_buff.position(),pkt,0,hdr.getLength());
            	break;
            	
            default:
            	System.err.println("Unknown message id "+hdr.getMessageId());
            	throw new IOException("Unknown message id");
			}	//End of switch
			
			while(checkptr < ptr+hdr.getLength()) {
				checksum+=m_rcv_buff.get();
				++checkptr;
			}
			ptr+=hdr.getLength();
			
		}	//End of L1_DATA_PACKET
		
		pktsum=m_rcv_buff.getShort();
		
		nextRef[0]=ptr;	//Pass in the "new next";
		
		System.err.println("Calculated checksum "+checksum+" packet checksum "+pktsum);
		if(checksum!=pktsum) {
			throw new IOException("Checksum mismatch");
		}
		
		return l1.getDirective() == L1_DATA_PACKET ? hdr.getMessageId(): l1.getDirective();
	}
	
	private void init_response_names() {
		response_name.put((int)L1_ACK_PACKET, "ACK");
		response_name.put((int)L1_NAK_PACKET, "NAK");
		
		response_name.put(M_DATA_RAW, "M_DATA_RAW");
		response_name.put(M_PROCESSED_VARS, "M_PROCESSED_VARS");
		response_name.put(M_PROCESSED_VARS_AND_SPECTRA, "M_PROCESSED_VARS_AND_SPECTRA");
		
		response_name.put(SER_IMPED_MSG, "SER_IMPED_MSG");
		response_name.put(SER_ERROR_MSG, "SER_ERROR_MSG");
		response_name.put(SER_REVISION_INFO, "SER_REVISION_INFO");
		
		response_name.put(M_PROCESSED_VARS_LABELS, "M_PROCESSED_VARS_LABELS");
		
		response_name.put(SER_EEG_SNIPPET_HEADER, "SER_EEG_SNIPPET_HEADER");
		response_name.put(SER_EEG_SNIPPET_RAW_DATA, "SER_EEG_SNIPPET_RAW_DATA");
		response_name.put(SER_EEG_SNIPPET_PROCESSED_DATA, "SER_EEG_SNIPPET_PROCESSED_DATA");
		response_name.put(SER_NO_SNIPPET, "SER_NO_SNIPPET");
		response_name.put(SER_SNIPPET_CORRUPTED, "SER_SNIPPET_CORRUPTED");
		
		response_name.put(SER_BIS_HISTORY_HEADER, "SER_BIS_HISTORY_HEADER");
		response_name.put(SER_BIS_HISTORY_DATA, "SER_BIS_HISTORY_DATA");
		response_name.put(SER_NO_HISTORY, "SER_NO_HISTORY");
		
		response_name.put(SER_EVENT_MSG, "SER_EVENT_MSG");

	}
	
	/**
	 * This probably isn't the most Java-esque way of doing it.
	 */
	private void init_wanted_fields() {
		wanted_fields=new Vector<>();
		
		Vector<String> v1=new Vector<>();
		v1.add("SR.*");
		v1.add("SR");
		
		Vector<String> v2=new Vector<>();
		v2.add("^(?!BISBIT).*BIS*.*");
		v2.add("BIS");
		
		Vector<String> v3=new Vector<>();
		v3.add("EMGLOW.*");
		v3.add("EMGLOW");
		
		Vector<String> v4=new Vector<>();
		v4.add("SQI.*");
		v4.add("SQI");
		
		wanted_fields.add(v1);
		wanted_fields.add(v2);
		wanted_fields.add(v3);
		wanted_fields.add(v4);
	}
	
	/**
	 * @deprecated Use the method with the timestamp.
	 * @param pkt_content
	 * @param type
	 */
	@Deprecated
	private void handle_packet(byte[] pkt_content, int type) {
		String isthere = response_name.get(type);
        String tname = (isthere!=null  ? isthere : "<unknown>");

        System.err.println("packet type "+tname);

        // actual handling
        switch (type) {
        case L1_ACK_PACKET:
                log.debug("ACK received");
                m_acked = 1;
                break;
        case L1_NAK_PACKET:
                log.error("NAK received");
                m_acked = 0;
                break;
        case SER_BIS_HISTORY_HEADER:
//                m_history_lock.lock();
//                m_history_header = *(pkt.history_info);
//                m_history_lock.unlock();
                break;
        case SER_BIS_HISTORY_DATA:
                //add_history_records(pkt.history_data);
                break;
        case SER_NO_HISTORY:
                log.debug("no more history");
//                m_history_lock.lock();
//                m_history_header.num_records = 0;
//                m_history_lock.unlock();
                break;
        case M_PROCESSED_VARS:
//                set_vars(ts, *(pkt.processed_vars));
                break;
        case M_PROCESSED_VARS_AND_SPECTRA:
//                set_vars(ts, pkt.processed_vars_and_spectra->processed_vars);
                break;
        case SER_REVISION_INFO:
                //set_revision_info(pkt_content);
                break;
        case M_PROCESSED_VARS_LABELS:
//                set_labels(pkt.processed_vars_labels);
                break;
        case SER_IMPED_MSG:
        case SER_ERROR_MSG:
        case SER_EVENT_MSG:
                //add_message(ts, type, pkt.string);
                break;
        default:
                log.error("not handling packet type %d (%s)", type, tname);
        }

		
	}
	
	private void set_revision_info(revision_info_msg packet) throws IOException {
		System.err.println("Setting revision info");
		temp_revision_info=packet;
		if(m_revision_info==null) {
			//No previous, just set this one.
			m_revision_info=packet;
		} else {
			if(!packet.equals(m_revision_info)) {
				throw new IOException("Mismatched revision info");
			}
			
		}
	}
	
	private void set_vars(long ts, final processed_vars_msg packet) {
		/*
		 * It's not clear what this does in the C++ code, but this is probably where we want
		 * to actually publish variables from.
		 */
		System.err.println("We got all the way to set_vars");
		System.err.println(packet.toString());
	}

	private void stop_raw_eeg() {
		// TODO Auto-generated method stub
		
	}

	private void turn_off_send_event() {
		// TODO Auto-generated method stub
		
	}

	private void turn_off_error_msg() {
		// TODO Auto-generated method stub
		
	}

	private void stop_processed_vars() {
		// TODO Auto-generated method stub
		
	}

	private void send_processed_vars(int i) {
		// TODO Auto-generated method stub
		
	}

	private void send_processed_vars_labels() {
		// TODO Auto-generated method stub
		
	}

	private void turn_on_send_event() {
		// TODO Auto-generated method stub
		
	}

	private void turn_on_imped_msg() {
		// TODO Auto-generated method stub
		
	}

	private void turn_on_error_msg() {
		// TODO Auto-generated method stub
		
	}

	private void send_revision_infox() {
		byte[] m_pkt=new byte[2*PAYLOAD_SIZE_MAX];
		ByteBuffer m_l1=ByteBuffer.allocate(8);
		m_l1.putShort((short)0xabba);		//start of packet
		m_l1.putShort(m_seq_id_host++);		//packet sequence id
		

		//now need int int short short for bis_a2k_payload_hdr
		ByteBuffer m_hdr=ByteBuffer.allocate(12);
		m_hdr.putInt(6);
		m_hdr.putInt(1004);
		m_hdr.putShort(m_acked>0 ? ++m_seq_num[1004] : m_seq_num[1004] );
		m_hdr.putShort( (short) 0);
		m_l1.putShort((short)12);							//m_l1->payload_size
		//can now write this down the output stream.
		
	}
	
	private void send_revision_info() throws IOException {
		bis_a2k_l1_hdr m_l1=new bis_a2k_l1_hdr();
		m_l1.setSeqId(m_seq_id_host++);
		//m_l1.setOptionalData((byte[])null);
		m_l1.setPayloadSize((short)0);
		m_l1.setDirective(L1_ACK_PACKET);
		
		bis_a2k_payload_hdr m_hdr=new bis_a2k_payload_hdr();
		m_hdr.setRoutingIdentifier(6);
		m_hdr.setMessageId(1004);
		m_hdr.setSeqNum(m_acked>0 ? ++m_seq_num[1004] : m_seq_num[1004]);
		m_hdr.setLength((short)0);
		m_l1.setPayloadSize((short)m_hdr.getBytes().length);
		
		//We aren't claiming any efficiency here - but it is easy enough to debug while we get going.
		byte[] ml1Bytes=m_l1.getBytes();
		byte[] mhdrBytes=m_hdr.getBytes();
		ByteBuffer bufferToSend=ByteBuffer.allocate(ml1Bytes.length+mhdrBytes.length+2);	//Add 2 for checksum;
		bufferToSend.put(ml1Bytes);
		bufferToSend.put(mhdrBytes);
		short sumToAppend=checksum(bufferToSend.array());
		bufferToSend.putShort(sumToAppend);
		byte[] bytes=bufferToSend.array();
		System.err.println("send_revision_info writing "+ArrayUtils.toString(bytes));
		toDevice.write(bytes);
		toDevice.flush();
		
	}
	
	private short checksum(byte[] toSum) {
		short sum=0;
		for(int i=0;i<toSum.length;i++) {
			sum+=toSum[i];
		}
		return sum;
	}
	
	private Thread dataThread;

	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		toDevice=new BufferedOutputStream(outputStream);
		fromDevice=new BufferedInputStream(inputStream);
		while(true) {
			if(!initDone) {
				System.err.println("process does not have initDone yet");
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				if(dataThread==null) {
					startDataThread();
				}
			}
		}
		

	}
	
	private boolean pleaseStop;
	
	private void startDataThread() {
		
		dataThread=new Thread() {

			@Override
			public void run() {
				boolean asked=false;
				if(!asked) {
					long timeout=500;
					log.info("Requesting version information");
					try {
						send( new byte[] {'D'}, 1 );	//Wrap and rethrow in c++ code not necessary here.
						asked=true;
					} catch (Exception e) {
						e.printStackTrace();
						log.error("Failed to send D requst", e);
					}
				}
				//After asking for data, it is supposed to come every five seconds.
				while(true) {
					try {
						sleep(4000);	//Sleep for four seconds.
						get_response("Data record", true, 2000);	//Then get the response with a timeout of 2 seconds.
					} catch (InterruptedException e) {
						if(pleaseStop) {
							return;
						}
					} catch(IOException ioe) {
						log.error("Failed to read data",ioe);
					}
					
				}
			}
			
		};
		dataThread.start();
	}

	@Override
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider serialProvider=super.getSerialProvider(idx);
		if(binary) {
			serialProvider.setDefaultSerialSettings(57600, DataBits.Eight , Parity.None, StopBits.One, FlowControl.None);
		} else {
			//Ascii mode.
			serialProvider.setDefaultSerialSettings(9600, DataBits.Eight , Parity.None, StopBits.One, FlowControl.None);
		}
		return serialProvider;
	}

	@Override
	protected long getMaximumQuietTime(int idx) {
		return 30_000;	//no idea yet if this is sensible.
	}

	@Override
	protected String iconResourceName() {
		return "bis.png";
	}
	
	

}

