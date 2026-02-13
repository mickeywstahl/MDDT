package org.mdpnp.apps.testapp.poclab;

import static org.junit.Assert.*;
import static org.mdpnp.apps.testapp.poclab.ASTMUtils.*;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

/**
 * The expected values in these tests were derived from a "known good" capture from an ABAXIS INC Piccolo Express device.
 */
public class ASTMUtilsTest {

	@Test
	public void testChecksum1() throws Exception {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		baos.write(STX);
		String dbg="1H|\\^&|||ABAXIS, INC.^piccolo xpress^3.1.37^0000P21592|||||||P|E 1394-97|20250814153900\r";
		System.err.println(dbg);
		baos.write( dbg.getBytes() );
		baos.write(ETX);
		String finalSum=ASTMChecksum(baos.toByteArray());
		assertEquals("Wrong checksum", "B1", finalSum);
	}
	
	@Test
	public void testChecksum2() throws Exception {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		baos.write(STX);
		baos.write( "2P|1|1239||||||U||||||^^|Patient||||||||||\r".getBytes() );
		baos.write(ETX);
		String finalSum=ASTMChecksum(baos.toByteArray());
		assertEquals("Wrong checksum", "94", finalSum);
	}
	
	@Test
	public void testChecksum3() throws Exception {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		baos.write(STX);
		baos.write( "3O|1|||^^^Comprehensive Metabolic: 5055AB0||20250701110717|||||||||||||||||||F\r".getBytes() );
		baos.write(ETX);
		String finalSum=ASTMChecksum(baos.toByteArray());
		assertEquals("Wrong checksum", "E5", finalSum);
	}
	
	@Test
	public void testChecksum4() throws Exception {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		baos.write(STX);
		baos.write( "4C|1|I|^^INST QC: OK    CHEM QC: OK|G\r".getBytes() );
		baos.write(ETX);
		String finalSum=ASTMChecksum(baos.toByteArray());
		assertEquals("Wrong checksum", "1F", finalSum);
	}

}
