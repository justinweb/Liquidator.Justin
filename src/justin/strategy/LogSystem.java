package justin.strategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

public class LogSystem {

	private ArrayList<ISimpleLog> allLogs = new ArrayList<ISimpleLog>();
	
	private static LogSystem Instance = null;
	private LogSystem()
	{		
	}
	
	public static LogSystem getInstance(){
		if( Instance == null )
			Instance = new LogSystem();
		
		return Instance;
	}	
	
	public void AttachLog( ISimpleLog logTarget )
	{
		allLogs.add(logTarget);
	}
	
	public void RemoveLog( ISimpleLog logTarget )
	{
		allLogs.remove(logTarget);
	}
	
	public void WriteLog( String msg ) throws IOException
	{
		Iterator<ISimpleLog> itr = allLogs.iterator();
		while( itr.hasNext())
		{
			itr.next().WriteLog(msg);
		}		
	}
	
	public void Clear()
	{
		allLogs.clear();
	}
	
	public static void main(String[] args)
	{
		System.out.println("Hello");
		FileLog fLog = null;
		try {
			fLog = new FileLog( "Test.log");
			LogSystem.getInstance().AttachLog(fLog);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		java.util.Date crtDate = new java.util.Date();
		java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat();
		dateFormat.applyPattern("yyyyMMdd_HHmmss");
		System.out.println( dateFormat.format(crtDate) );
		
		java.util.HashMap<String, Double> dicUpLimit = new java.util.HashMap<String,Double>();
		dicUpLimit.put("TXO08300J3", 10.0);
		if( dicUpLimit.containsKey("TXO08300J3"))
			System.out.println(dicUpLimit.get("TXO08300J3"));
		else
			System.out.println("no value");
		
		dicUpLimit.put("TXO08300J3", 10.0);
		
	}
}
