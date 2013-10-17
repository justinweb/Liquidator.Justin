package justin.strategy;

import java.io.*;

public class FileLog  implements ISimpleLog {
	
	private BufferedWriter log = null;
	
	public FileLog( String logName ) throws IOException 
	{
		try {
			log = new BufferedWriter( new FileWriter( logName ));
		} catch (IOException e) {
			throw e;
		}
	}
	
	public void Close() throws IOException
	{
		if( log != null )
			try {
				log.close();
			} catch (IOException e) {
				throw e;
			}
	}
	
	public void WriteLog( String msg ) throws IOException
	{
		if( log != null )
			try {
				log.write(msg);
				log.newLine();
				log.flush();
			} catch (IOException e) {
				throw e;
			}
	}

}
