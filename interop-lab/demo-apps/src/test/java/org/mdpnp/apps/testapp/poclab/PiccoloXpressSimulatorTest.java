package org.mdpnp.apps.testapp.poclab;

import static org.junit.Assert.*;

import java.util.ArrayList;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mdpnp.apps.testapp.FxRuntimeSupport;

public class PiccoloXpressSimulatorTest {
	
	/**
	 * String containing the possible octal values to compare against
	 */
	static final String octals="01234567";
	
	/**
	 * Get the index of \r in bytes, assuming that \r is followed by ETX, as it should be
	 * in a correctly formed record.  As opposed to \r\n that appear later, after the checksum
	 * @param bytes
	 * @return
	 */
	private int getSlashRPos(byte[] bytes) {
		int slashRPos=0;
		for(int i=1;i<bytes.length;i++) {
			if(bytes[i]=='\r' && bytes[i+1]==ASTMUtils.ETX) {
				slashRPos=i;
				break;
			}
		}
		return slashRPos;
	}

	@Test
	public void testCreateHL7ResultsForPiccoloNormal() {
		PiccoloXpressSimulator simulator=new PiccoloXpressSimulator();
		simulator.qcFailureProperty.set(false);
		simulator.startProcess();
		ArrayList<byte[]> resultsArray=simulator.createHL7ResultsForPiccolo();
		assertEquals("Wrong number of results", 43, resultsArray.size());
		
		//The number of actual test results.
		int numOfResults=0;
		for(byte[] result : resultsArray) {
			assertEquals("ASTM Record did not start with STX", result[0], ASTMUtils.STX);
			//There should be a \r without a \n next to it as part of the record.
			int slashRPos=getSlashRPos(result);
			assertFalse("No \\r separator in record",slashRPos==0);
			String content=new String(result,1,slashRPos-1);
			char shouldBeOctal=content.charAt(0);
			assertTrue("Invalid octal value "+shouldBeOctal,octals.indexOf(shouldBeOctal)!=-1);
			if(content.charAt(1)=='R' && content.indexOf("^^^")==-1) {
				//System.err.println(content);
				//This is an actual test result, as opposed to a Quality Control report
				numOfResults++;
				String[] fields=content.split("|");
				assertEquals("All test results should be normal",-1,fields[3].indexOf('*'));
			}
		}
		assertEquals("Incorrect number of actual results", 14, numOfResults);
		
	}
	
	@Test
	public void testCreateHL7ResultsForPiccoloRenalFailure() {
		PiccoloXpressSimulator simulator=new PiccoloXpressSimulator();
		simulator.qcFailureProperty.set(false);
		simulator.renalFailureProperty.set(true);
		simulator.startProcess();
		ArrayList<byte[]> resultsArray=simulator.createHL7ResultsForPiccolo();
		assertEquals("Wrong number of results", 43, resultsArray.size());
		
		//The number of actual test results.
		int numOfResults=0, inLimits=0,outOfLimits=0;
		for(byte[] result : resultsArray) {
			assertEquals("ASTM Record did not start with STX", result[0], ASTMUtils.STX);
			//Next, find the position of single '\r' representing the end of the string content
			int slashRPos=getSlashRPos(result);
			assertFalse("No \r separator in record",slashRPos==0);
			String content=new String(result,1,slashRPos-1);
			
			char shouldBeOctal=content.charAt(0);
			assertTrue("Invalid octal value "+shouldBeOctal,octals.indexOf(shouldBeOctal)!=-1);
			if(content.charAt(1)=='R' && content.indexOf("^^^")==-1) {
				//System.err.println(content);
				//This is an actual test result, as opposed to a Quality Control report
				numOfResults++;
				String[] fields=content.split("\\|");
				if(
					fields[2].equals("2823-3^^LN^Potassium SerPl-sCnc") ||
					fields[2].equals("3094-0^^LN^BUN SerPl-mCnc") ||
					fields[2].equals("2160-0^^LN^Creat SerPl-mCnc")
				) {
					//These should be out of limits, and so contain an asterisk
					assertTrue("Metric should have been out of limits for renal failure",fields[3].indexOf('*')!=-1);
					outOfLimits++;
				} else {
					//These should be inside limits, and so should not contain an asterisk
					assertFalse("Metric should have been inside limits for renal failure",fields[3].indexOf('*')!=-1);
					inLimits++;
				}
			}
			//System.err.println("num of results is "+numOfResults);
		}
		assertEquals("Incorrect number of actual results", 14, numOfResults);
		assertEquals("Incorrect number of out of limits results", 3, outOfLimits);
		assertEquals("Incorrect number of in limits results", 11, inLimits);
	}

	@Test
	public void testFasted() {
		PiccoloXpressSimulator simulator=new PiccoloXpressSimulator();
		simulator.qcFailureProperty.set(false);
		simulator.renalFailureProperty.set(true);
		simulator.noFastedProperty.set(false);
		simulator.startProcess();
		String lipemia=simulator.lipemiaLabelText.get();
		System.err.println(lipemia);
		assertTrue(lipemia.endsWith("(Normal)"));
	}

	@Test
	public void testNotFasted() {
		PiccoloXpressSimulator simulator=new PiccoloXpressSimulator();
		simulator.qcFailureProperty.set(false);
		simulator.renalFailureProperty.set(true);
		simulator.noFastedProperty.set(true);
		simulator.startProcess();
		String lipemia=simulator.lipemiaLabelText.get();
		System.err.println(lipemia);
		assertTrue(lipemia.endsWith("(Moderate - High)"));
	}
	
	@Test
	public void testDiabetes() {
		PiccoloXpressSimulator simulator=new PiccoloXpressSimulator();
		simulator.diabetesProperty.set(true);
		simulator.startProcess();
		ArrayList<byte[]> resultsArray=simulator.createHL7ResultsForPiccolo();
		assertEquals("Wrong number of results", 43, resultsArray.size());
		
		//The number of actual test results.
		int numOfResults=0,inLimits=0,outOfLimits=0;;
		for(byte[] result : resultsArray) {
			assertEquals("ASTM Record did not start with STX", result[0], ASTMUtils.STX);
			//Next, find the position of single '\r' representing the end of the string content
			int slashRPos=getSlashRPos(result);
			assertFalse("No \r separator in record",slashRPos==0);
			String content=new String(result,1,slashRPos-1);
			
			char shouldBeOctal=content.charAt(0);
			assertTrue("Invalid octal value "+shouldBeOctal,octals.indexOf(shouldBeOctal)!=-1);
			if(content.charAt(1)=='R' && content.indexOf("^^^")==-1) {
				//System.err.println(content);
				//This is an actual test result, as opposed to a Quality Control report
				numOfResults++;
				String[] fields=content.split("\\|");
				System.err.println(fields[2]);
				if(
					fields[2].equals("2345-7^^LN^Glucose SerPl-mCnc")
				) {
					//These should be out of limits, and so contain an asterisk
					assertTrue("Metric should have been out of limits for renal failure",fields[3].indexOf('*')!=-1);
					outOfLimits++;
				} else {
					//These should be inside limits, and so should not contain an asterisk
					assertFalse("Metric should have been inside limits for renal failure",fields[3].indexOf('*')!=-1);
					inLimits++;
				}
			}
			//System.err.println("num of results is "+numOfResults);
		}
		assertEquals("Incorrect number of actual results", 14, numOfResults);
		assertEquals("Incorrect number of out of limits results", 1, outOfLimits);
		assertEquals("Incorrect number of in limits results", 13, inLimits);
	}

}
