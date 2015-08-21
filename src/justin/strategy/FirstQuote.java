package justin.strategy;

import java.awt.List;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;

import com.orcsoftware.liquidator.Future;
import com.orcsoftware.liquidator.Instrument;
import com.orcsoftware.liquidator.InstrumentFilter;
import com.orcsoftware.liquidator.InstrumentKind;
import com.orcsoftware.liquidator.LiquidatorModule;
import com.orcsoftware.liquidator.LiquidatorRuntime;
import com.orcsoftware.liquidator.MarketData;
import com.orcsoftware.liquidator.MassQuoteAck;
import com.orcsoftware.liquidator.MassQuoteEntry;
import com.orcsoftware.liquidator.MassQuotePackage;
import com.orcsoftware.liquidator.MassQuoteRequest;
import com.orcsoftware.liquidator.Option;
import com.orcsoftware.liquidator.Order;
import com.orcsoftware.liquidator.OrderParameters;
import com.orcsoftware.liquidator.QuotePair;
import com.orcsoftware.liquidator.QuoteRejectInfo;
import com.orcsoftware.liquidator.Side;
import com.orcsoftware.liquidator.Strategy;

public class FirstQuote extends LiquidatorModule {
	
	private static final String INSTRUMENT_PARAMETER = "instrument";
	private static final String PRICE_PARAMETER = "price";
	private static final String LOTS_PARAMETER = "lots";
	private static final String BATCHCOUNT_PARAMETER = "batchCount";
	private static final String ACTIVE_PARAMETER = "active";
	private static final String ORDERMODE_PARAMETER = "orderMode";
	private static final String SIMULATION_PARAMETER = "simulation";
	private static final String SIDE_PARAMETER = "side";
	private static final String TRADERID_PARAMETER = "traderid";
	private static final String ACCOUNT_PARAMETER = "account";
	private static final String ORDERPARAM_TRADERID = "M2";
	private static final String ORDERPARAM_ACCOUNT = "AC";
	private static final String SELSYMBOL_PARAMETER = "selsymbol";
	private static final String DELAY_PARAMETER	= "Delay";
	private static final String PARAM_AskPrice	= "AskPrice";
	private static final String PARAM_AskQty	= "AskQty";
	private static final String PARAM_BidPrice	= "BidPrice";
	private static final String PARAM_BidQty	= "BidQty";
	private static final String PARAM_TickDiff	= "TickDiff";
	
	private static final String VERSION	= "20150821.1";
	
	private FileLog fLog = null;
	
	public enum OrderModeEnum{
		SingleOrder,
		BatchOrder,
		SingleQuote,
		BatchQuote
	}
	
	private class StatisticData{
		public long Start_ns = 0;
		public long End_ns = 0;
		
		public StatisticData(){}
		public StatisticData( long start, long end ){
			Start_ns = start;
			End_ns = end;
		}
		public long Diff(){
			return End_ns - Start_ns;
		}
		public String ToString(){
			return String.format("Start=%d,End=%d,Diff=%d", Start_ns, End_ns, Diff() );
		}
	}
	
	private class StatisticCalcResult{
		public int Count = 0;
		public double Average = 0.0;
		public double Median = 0.0;
		public double StdDev = 0.0;
	}
	
	private class StatisticBatch{
		private int BatchCount = 0;
		private StatisticData[] Data = null;
		private int crtIndex = 0;
		
		public StatisticBatch( int count ){
			BatchCount = count;
			Data = new StatisticData[count];
		}
		
		public boolean AddData( StatisticData newData ){
			if( crtIndex >= BatchCount )
				return false;
			
			Data[crtIndex++] = newData;
			
			return true;
		}
		
		public void Clear(){
			crtIndex = 0;
			for( StatisticData d : Data ){
				d.Start_ns = d.End_ns = 0;
			}
		}
		
		public StatisticCalcResult Calculate(){
			StatisticCalcResult result = new StatisticCalcResult();
			if( crtIndex <= 0 )
				return result;
			

			
			double totalDiff = 0.0;
			int numOfData = 0;
			for( StatisticData oneData : Data ){
				totalDiff += oneData.Diff();
				++numOfData;
			}
			result.Count = numOfData;
			if( numOfData > 0 ){
				double[] medianData = new double[numOfData];
				int medianDataIndex = 0;
				
				result.Average = totalDiff / numOfData;
				// calculate std. dev.
				double totalDev = 0.0;
				for( StatisticData oneData : Data ){
					totalDev += (result.Average - oneData.Diff()) * (result.Average - oneData.Diff());
					medianData[medianDataIndex++] = oneData.Diff();
				}
				result.StdDev = Math.sqrt( totalDev / numOfData );
				
				// calculate median
				Arrays.sort( medianData );
				if (medianData.length % 2 == 0) 
			    {
			       result.Median = (medianData[(medianData.length / 2) - 1] + medianData[medianData.length / 2]) / 2.0;
			    } 
			    else 
			    {
			       result.Median = medianData[medianData.length / 2];
			    }
			}
			
			return result;
		}
		
		public int GatBatchCount() { return BatchCount; }
		public int GetCrtIndex() { return crtIndex; }
		public StatisticData[] GetData() { return Data; }
	}
	
	private class MassQuoteData{
		public long ID = 0;
		public long StartTime_ns = 0;
		public long EndTime_ns = 0;
		public String Feedcode = "";
	}
	
	private class FirstQuoteStatus
	{
		public Instrument Product = null;
		public long Lots = 1;
		public double Price = 1.0;
		public long BatchCount = 0;
		public OrderParameters OrderParam = null;
		public long OrderMode = 3;		
		public long IsSimulation = 1;
		public long Side = 1;
		public long DelayTimeMS = 0;		// delay time between every orders
		public long AskQty = 0;
		public long BidQty = 0;
		public double AskPrice = 0.0;
		public double BidPrice = 0.0;
		public double TickDiff = 0.0;		// tick price diff for adjust the bid and ask price for batch sending quotes		
		
		public Set<Instrument> BatchQuoteInstruments = new HashSet<Instrument>();
		//public ArrayList BatchQuoteStartTime = new ArrayList();
		//public Queue<Long> BatchQuoteStartTime = new LinkedList<Long>();
		public Map<Long,MassQuoteData> BatchQuoteMQData = new HashMap<Long,MassQuoteData>();
		public Set<MassQuotePackage> BatchQuote = new HashSet<MassQuotePackage>();
		
		public long StartSendQuote_ns = 0;
		public long EndSendQuote_ns = 0;
		
		public StatisticBatch statisticBatch = null;
		
		public FirstQuoteStatus()
		{
			OrderParam = new OrderParameters();
			OrderParam.setAdditionalData(ORDERPARAM_ACCOUNT, "0000000");
			OrderParam.setAdditionalData(ORDERPARAM_TRADERID, "070QAA");	
		}
		
		public String toString()
		{
			String traderID = "";
			if( OrderParam != null )
				traderID = OrderParam.getAdditionalData(ORDERPARAM_TRADERID);
			
			return String.format("Lots=%d,Price=%f,BatchCount=%d,OrderMode=%d,NumOfSeries=%d,IsSimulation=%d,Side=%d,TraderID=%s,Bid(%d@%f),Ask(%d@%f),TickDiff(%f)",
					Lots,Price,BatchCount,OrderMode,0,IsSimulation,Side,
					traderID == null ? "":traderID, BidQty, BidPrice, AskQty,AskPrice, TickDiff
					);
		}
	}
	
	public String GetDateTimeString(){
		java.util.Date crtDate = new java.util.Date();
		java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat();
		dateFormat.applyPattern("yyyyMMdd_HHmmss");
		return dateFormat.format(crtDate);
	}
	
	public void onCreate() {
		Strategy strategy = runtime.getStrategy();
		
		try
		{			
			java.util.Date crtDate = new java.util.Date();
			java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat();
			dateFormat.applyPattern("yyyyMMdd");
			String logName = "/orcreleases/logs/FirstQuote_" + dateFormat.format(crtDate) + ".txt";
			
			fLog = new FileLog( logName );
			LogSystem.getInstance().AttachLog(fLog);
			fLog.WriteLog( String.format("%s Log start ... >>>> ", GetDateTimeString()) );
			fLog.WriteLog("Version:" + VERSION );
			
			runtime.log("FirstQuote", VERSION );
		}
		catch( Exception exp )
		{
			runtime.log("FirstQuote", exp.toString());
			runtime.log("Craete and set log failed");
		}
		
		try{
			// get parameters
			FirstQuoteStatus state = new FirstQuoteStatus();
			state.Product = strategy.getInstrumentParameter(INSTRUMENT_PARAMETER);	
			
			if( state.Product == null ){
				runtime.log( "FirstQuote", "get instrument failed");
			}else{
				runtime.log( "FirstQuote", "get instrument OK");
				strategy.setState(state);
				try {
					LogSystem.getInstance().WriteLog("Set State," + state.toString());
				} catch (IOException exp) {
					// TODO Auto-generated catch block
					runtime.log(exp.toString());
				}
				
				runtime.log( "FirstQuote", "Subscribe MD");
				state.Product.marketDataSubscribe();				

				runtime.log( "FirstQuote", "OnCreate() GetParameters()");
				GetParameters();
				
				// show selected instrument
				strategy.setParameter(SELSYMBOL_PARAMETER, state.Product.getFeedcode() );
				
				PrepareBatchQuoteInstruments( state.Product );
				
			}
		}catch( Exception exp ){
			runtime.log("FirstQuote", exp.toString());
			runtime.log("FirstQuote", "get parameters failed");
		}
		
		runtime.log( "FirstQuote", "OnCreate() end" );
		
	}
	
	public void onDelete(){
		
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus)strategy.getState();
		
		if( state != null && state.Product != null)
		{
			state.Product.marketDataUnsubscribe();
		}		
		
		LogSystem.getInstance().Clear();
		
		if( fLog != null )
		{
			try
			{
				fLog.WriteLog("Log stop ... <<<< ");
				fLog.Close();
			}
			catch( Exception exp )
			{
				runtime.log(exp.toString());
			}
		}
	}
	
	public void onStart(){
		
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus)strategy.getState();
		
		// update parameters
		GetParameters();
		
		//ProductLimit limit = getProductLimit( state.Product );		
	}
	
	public void onStop(){
		runtime.log("Stop");
		int cancelOrders = 0;
		for (Order order : runtime.getStrategy().getOrders()) {
			order.remove();
			++cancelOrders;
		}		
		runtime.log("FirstQuote", String.format("Cancel %d orders", cancelOrders ));
		
		CancelQuote();
	}
	
	public void onParameterChanged(){		
		
		runtime.log( "Parameter changed ");		 
		GetParameters(); 
		
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		long isActive = strategy.getLongParameter(ACTIVE_PARAMETER);		
		if( isActive > 0 )
		{			
			switch( (int)state.OrderMode ){
			case 0: // SingleOrder
				break;
			case 1: // BatchOrder
				break;
			case 2: // SingleQuote
				state.statisticBatch = new StatisticBatch( (int)state.BatchCount );
				SendQuote();
				break;
			case 3: // BatchQuote
				state.statisticBatch = new StatisticBatch( state.BatchQuoteInstruments.size() );
				state.BatchQuoteMQData.clear();
				BatchQuote();
				break;
			default:
				runtime.log("FirstQuote", "Unknown OrderMode=" + state.OrderMode );
				break;
			}

		}	
	}
	
	private void PrepareBatchQuoteInstruments( Instrument product ){
//		String feedcode = product.getFeedcode();
//		InstrumentKind myKind = product.getKind();
//		if( myKind == InstrumentKind.CALL || myKind == InstrumentKind.PUT ){
//			Option opt = (Option) product;
//			double strike = opt.getStrikePrice();
//		}
		
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		
		String[] testFeedcode = new String[]{ "TXO06800I5", "TXO06900I5", "TXO07000I5" , "TXO07100I5", "TXO07200I5", "TXO07300I5", "TXO07400I5", "TXO07500I5", "TXO07600I5", "TXO07700I5" };
		for( String feedcode : testFeedcode ){
			Instrument newInst = runtime.getInstrumentManager().getInstrument( product.getMarketName(), feedcode );
			if( newInst != null ){
				runtime.log("FirstQuote", "Got Instrument=" + newInst.getFeedcode() );
				
				state.BatchQuoteInstruments.add( newInst );
				
			}else{
				runtime.log(LiquidatorRuntime.LOG_WARNING, "FirstQtuoe", "Can not get instrument=" + feedcode );
			}
		}
	}
	
	private void PrepareMassQuoteRequestEntry(){
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		
		state.BatchQuote.clear();
		for( Instrument inst : state.BatchQuoteInstruments ){
			MassQuotePackage mqPack = new MassQuotePackage();
			MassQuoteEntry quoteEntry = new MassQuoteEntry( inst,state.BidQty, state.BidPrice, state.AskQty, state.AskPrice);
			mqPack.addMassQuoteRequestEntry(quoteEntry);
			
			state.BatchQuote.add( mqPack );
		}
	}
	
	private ProductLimit getProductLimit( Instrument product )
	{
		try
		{
			// special value is from OP_sec.pdf p.91
			MarketData md = product.getMarketData();
			if( md == null ) 
				runtime.log( product.getFeedcode() + "'MD is null");
			else
			{
				ProductLimit limit = new ProductLimit();
				if( md.getMarketSpecificValue("upper_limit") != null )
					limit.UpLimit = (double)md.getMarketSpecificValue("upper_limit");
				if( md.getMarketSpecificValue("lower_limit") != null )
					limit.DownLimit = (double)md.getMarketSpecificValue("lower_limit");
				return limit;
			}
			
			return null;
		}
		catch( Exception exp)
		{
			runtime.log(exp.toString());
			return null;
		}
	}
	
	private void GetParameters()
	{
		try
		{
			runtime.log( "FirstQuote", "GetParameters()");
			LogSystem.getInstance().WriteLog("GetParameters() ..." );
			
			Strategy strategy = runtime.getStrategy();
			FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();

			double price = strategy.getDoubleParameter(PRICE_PARAMETER);
			long lots = strategy.getLongParameter(LOTS_PARAMETER);
			long batchCount = strategy.getLongParameter(BATCHCOUNT_PARAMETER);			
			long orderMode = strategy.getLongParameter(ORDERMODE_PARAMETER);
			long isSimulation = strategy.getLongParameter(SIMULATION_PARAMETER);
			long side = strategy.getLongParameter(SIDE_PARAMETER);
			long delayTimeMS = strategy.getLongParameter(DELAY_PARAMETER);
			String traderID = (String)strategy.getParameter(TRADERID_PARAMETER);
			String account = (String)strategy.getParameter(ACCOUNT_PARAMETER);
			
			double askPrice = strategy.getDoubleParameter(PARAM_AskPrice);
			double bidPrice = strategy.getDoubleParameter(PARAM_BidPrice);
			
			long askQty = strategy.getLongParameter(PARAM_AskQty);
			long bidQty = strategy.getLongParameter(PARAM_BidQty);
			
			double tickDiff = strategy.getDoubleParameter(PARAM_TickDiff);

			state.Price = price;
			if( lots >= 0 )
				state.Lots = lots;
			else
			{
				runtime.log("Lots is incorrect.");
				strategy.setParameter(LOTS_PARAMETER, state.Lots);
			}
			if( batchCount >= 0)
				state.BatchCount = batchCount;
			else
			{
				runtime.log("BatchCount is incorrect.");
				strategy.setParameter(BATCHCOUNT_PARAMETER, state.BatchCount);
			}
			if( orderMode >= 0 && orderMode <= 3)
				state.OrderMode = orderMode;
			else
			{
				runtime.log("OrderMode is incorrect.");
				strategy.setParameter(ORDERMODE_PARAMETER, state.OrderMode);
			}
			if( isSimulation >= 0 && isSimulation <= 1)
				state.IsSimulation = isSimulation;
			else
			{
				runtime.log("IsSimulation is incorrect.(1:Simulation,0:Order)");
				strategy.setParameter(SIMULATION_PARAMETER, 1);
			}
			if( side >= 1 && side <= 2)
				state.Side = side;
			else
			{
				runtime.log("Side is incorrect.( Buy=>1, Sell=>2 )");
				strategy.setParameter(SIDE_PARAMETER, state.Side);
			}
			if( traderID.length() > 0)
				state.OrderParam.setAdditionalData(ORDERPARAM_TRADERID, traderID);
			else
			{
				runtime.log("TraderID is incorrect.");
				strategy.setParameter(TRADERID_PARAMETER, state.OrderParam.getAdditionalData(ORDERPARAM_TRADERID));
			}
			if( account.length() > 0 ){
				state.OrderParam.setAdditionalData(ORDERPARAM_ACCOUNT, account);
			}else{
				runtime.log("TraderID is incorrect.");
				strategy.setParameter(ACCOUNT_PARAMETER, state.OrderParam.getAdditionalData(ORDERPARAM_ACCOUNT));
			}
			if( delayTimeMS >= 0 ){
				state.DelayTimeMS = delayTimeMS;				
				LogSystem.getInstance().WriteLog("Set DelayTimeMS = " + state.DelayTimeMS );
			}
			if( askQty > 0 ) state.AskQty = askQty;
			if( bidQty > 0 ) state.BidQty = bidQty;
			if( askPrice > 0.0 ) state.AskPrice = askPrice;
			if( bidPrice > 0.0 ) state.BidPrice = bidPrice;
			if( tickDiff > 0.0 ) state.TickDiff = tickDiff;
			
			LogSystem.getInstance().WriteLog("Update State," + state.toString());	
			runtime.log( "FirstQuote", "GetParameters() end");
		}
		catch(Exception exp)
		{
			runtime.log(exp.toString());
		}
	}
	
	private void SendQuote(){
		try{
			LogSystem.getInstance().WriteLog("SendQuote ..." );
			
			Strategy strategy = runtime.getStrategy();
			FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();			
			
			String msg;
			msg = String.format( "SendQuote Symbol=%s,Bid=%d@%f,Ask=%d@%f", state.Product.getFeedcode(), state.BidQty, state.BidPrice, state.AskQty, state.AskPrice );			
			LogSystem.getInstance().WriteLog(msg);						
						
			MassQuotePackage mqPack = new MassQuotePackage();
			MassQuoteEntry quoteEntry = new MassQuoteEntry( state.Product,state.BidQty, state.BidPrice, state.AskQty, state.AskPrice);
			mqPack.addMassQuoteRequestEntry(quoteEntry);
			
			state.StartSendQuote_ns = System.nanoTime();
			runtime.getOrderManager().massQuote((MassQuoteRequest)mqPack, state.OrderParam);
			//runtime.getOrderManager().quote(state.Product, state.BidQty, state.BidPrice, state.AskQty, state.AskPrice, state.OrderParam );			
			
			LogSystem.getInstance().WriteLog("SendQuote End" );
		}catch( Exception exp ){
			runtime.log(exp.toString());
		}
	}
	
	private void BatchQuote(){
		try{
			LogSystem.getInstance().WriteLog("BatchQuote ..." );
			
			Strategy strategy = runtime.getStrategy();
			FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
			
			String msg;
//			for( Instrument inst : state.BatchQuoteInstruments ){			
//				
//				msg = String.format( "SendQuote Symbol=%s,Bid=%d@%f,Ask=%d@%f",inst.getFeedcode(), state.BidQty, state.BidPrice, state.AskQty, state.AskPrice );
//				LogSystem.getInstance().WriteLog(msg);				
//				
//				MassQuoteData mqData = new MassQuoteData();
//				mqData.Feedcode = inst.getFeedcode();
//				mqData.StartTime_ns = System.nanoTime();
//				
//				MassQuotePackage mqPack = new MassQuotePackage();				
//				MassQuoteEntry quoteEntry = new MassQuoteEntry( inst,state.BidQty, state.BidPrice, state.AskQty, state.AskPrice);
//				mqPack.addMassQuoteRequestEntry(quoteEntry);
//				mqData.ID = runtime.getOrderManager().massQuote((MassQuoteRequest)mqPack, state.OrderParam);
//				
//				state.BatchQuoteMQData.put( mqData.ID, mqData );
//				//runtime.getOrderManager().quote(inst, state.BidQty, state.BidPrice, state.AskQty, state.AskPrice, state.OrderParam );			
//			}
			
			PrepareMassQuoteRequestEntry();
			
			for( MassQuotePackage mqPack : state.BatchQuote ){				
				
				MassQuoteData mqData = new MassQuoteData();
				mqData.Feedcode = mqPack.getMassQuoteRequestEntries()[0].getInstrument().getFeedcode();
				mqData.StartTime_ns = System.nanoTime();
				
				mqData.ID = runtime.getOrderManager().massQuote((MassQuoteRequest)mqPack, state.OrderParam);
				state.BatchQuoteMQData.put( mqData.ID, mqData );
				
				msg = String.format( "SendQuote Symbol=%s,Bid=%d@%f,Ask=%d@%f",mqData.Feedcode, state.BidQty, state.BidPrice, state.AskQty, state.AskPrice );
				LogSystem.getInstance().WriteLog(msg);
			}
			
			LogSystem.getInstance().WriteLog("SendQuote End" );
		}catch( Exception exp ){
			runtime.log(exp.toString());
		}
	}
	
	private void CancelQuote(){
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		
		QuotePair quotepair = runtime.getOrderManager().getQuote( state.Product );
		if (quotepair!=null) {
			String removeQuoteLog = "";
			if (quotepair.getQuote(Side.SELL)!=null){
				quotepair.getQuote(Side.SELL).remove();
				removeQuoteLog += "Remove Sell side";
			}
			if (quotepair.getQuote(Side.BUY)!=null){
				quotepair.getQuote(Side.BUY).remove();
				removeQuoteLog += " " + "Remove Buy side";
			}
			runtime.log("FirstQuote", removeQuoteLog );
		}
		
		for( Instrument inst : state.BatchQuoteInstruments ){
			quotepair = runtime.getOrderManager().getQuote( inst );
			if (quotepair!=null) {
				String removeQuoteLog = "Cancel " + inst.getFeedcode() + " ";
				if (quotepair.getQuote(Side.SELL)!=null){
					quotepair.getQuote(Side.SELL).remove();
					removeQuoteLog += "Remove Sell side";
				}
				if (quotepair.getQuote(Side.BUY)!=null){
					quotepair.getQuote(Side.BUY).remove();
					removeQuoteLog += " " + "Remove Buy side";
				}
				runtime.log("FirstQuote", removeQuoteLog );
			}
		}
	}
	
	// for FixOut
	public void OnQuoteStatus(){
		runtime.log( "FirstQuote", "OnQuoteStatus()..." );
		
		long timestamp = System.nanoTime();
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		
		switch( (int)state.OrderMode ){
		case 0: // SingleOrder
			break;
		case 1: // BatchOrder
			break;
		case 2: // SingleQuote
			OnQuoteStatusSingleQuote( timestamp );
			break;
		case 3: // BatchQuote
			OnQuoteStatusBatchQuote( timestamp );
			break;
		default:
			runtime.log(LiquidatorRuntime.LOG_WARNING, "FirstQuote", "Unknown OrderMode="+state.OrderMode);
			break;
		}
	}
	
	private void OnQuoteStatusSingleQuote( long timestamp ){
		
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		state.EndSendQuote_ns = timestamp;
		
		runtime.log( "FirstQuote", String.format("Send Quote %d ns", state.EndSendQuote_ns - state.StartSendQuote_ns ) );
		
		if( state.statisticBatch.AddData( new StatisticData( state.StartSendQuote_ns, state.EndSendQuote_ns) ) == false ){
			runtime.log( "FirstQuote", "Add StatisticData failed" );
		}else{
			runtime.log( "FirstQuote", String.format( "CrtIndex=%d,BatchCount=%d", state.statisticBatch.GetCrtIndex(),state.statisticBatch.GatBatchCount() ));
			if( state.statisticBatch.GetCrtIndex() < state.statisticBatch.GatBatchCount() ){
				state.BidPrice += state.TickDiff;
				state.AskPrice += state.TickDiff;
				SendQuote();
			}else{
				ShowBatchTestResult();
				strategy.setParameter(ACTIVE_PARAMETER, 0);
			}
		}
	}
	
	private void OnQuoteStatusBatchQuote( long timestamp ){
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();

//		if( state.BatchQuoteStartTime.size() > 0 ){
//			long timestampStart = state.BatchQuoteStartTime.remove();
//			
//			if( state.statisticBatch.AddData( new StatisticData( timestampStart, timestamp) ) == false ){
//				runtime.log( "FirstQuote", "Add StatisticData failed" );
//			}else{
//				runtime.log( "FirstQuote", String.format( "CrtIndex=%d,BatchCount=%d", state.statisticBatch.GetCrtIndex(),state.statisticBatch.GatBatchCount() ));
//				if( state.statisticBatch.GetCrtIndex() >= state.statisticBatch.GatBatchCount() ){
//					ShowBatchTestResult();
//				}
//			}
//		}		
		
	}
	
	private void ShowBatchTestResult(){
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		
		runtime.log( "FirstQuote", "Batch Test is finished, count=" + state.BatchCount );
		
		for( StatisticData oneData : state.statisticBatch.GetData() ){
			runtime.log( "FirstQuote", String.format( "Start=%d,End=%d,Diff=%d", oneData.Start_ns, oneData.End_ns, oneData.Diff()) );
		}
		
		StatisticCalcResult calcResult = state.statisticBatch.Calculate();
		runtime.log( "FirstQuote", "Batch Test : avg " + calcResult.Average + " ns,count=" + calcResult.Count + ",Median=" + calcResult.Median + ",StdDev.=" + calcResult.StdDev );
	}
	
	// For GDK Gateway MassQuote service
	public void onMassQuoteAck(MassQuoteAck massQuoteAck) {
		runtime.log( "FirstQuote", "OMassQuoteAck()...,ID=" + massQuoteAck.getMassQuoteIdentifier() );
		long timestamp = System.nanoTime();
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		
		switch( (int)state.OrderMode ){
		case 0: // SingleOrder
			break;
		case 1: // BatchOrder
			break;
		case 2: // SingleQuote
			OnMassQuoteAckSingle( massQuoteAck, timestamp );
			break;
		case 3: // BatchQuote
			OnMassQuoteAckBatch( massQuoteAck, timestamp );
			break;
		default:
			runtime.log(LiquidatorRuntime.LOG_WARNING, "FirstQuote", "Unknown OrderMode="+state.OrderMode);
			break;
		}
	}
	
	private void OnMassQuoteAckSingle(MassQuoteAck massQuoteAck, long endTime_ns ){
		long mqID = massQuoteAck.getMassQuoteIdentifier();
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
			
		runtime.log("FirstQuote", String.format("MQID=%d,Start=%d,End=%d", mqID, state.StartSendQuote_ns, endTime_ns) );
		if( state.statisticBatch.AddData( new StatisticData( state.StartSendQuote_ns, endTime_ns ) ) == false ){
			runtime.log(LiquidatorRuntime.LOG_ERROR, "FirstQuote", String.format("Add MQID=%d failed",mqID)  );
		}else{
			if( state.statisticBatch.GetCrtIndex() >= state.statisticBatch.GatBatchCount() ){
				ShowBatchTestResult();
				strategy.setParameter(ACTIVE_PARAMETER, 0);
			}else{
				state.BidPrice += state.TickDiff;
				state.AskPrice += state.TickDiff;
				
				SendQuote();
			}
		}

	}
	
	
	private void OnMassQuoteAckBatch(MassQuoteAck massQuoteAck, long endTime_ns ){		
		
		QuoteRejectInfo[] rejectInfo = massQuoteAck.getRejections();
		if (rejectInfo == null || rejectInfo.length <= 0) {
			runtime.log("Mass quote status [" + massQuoteAck.getMassQuoteAckType().toString() + "]");
	    } else {
	    	for (int i = 0; i < rejectInfo.length; i++) {
	    		runtime.log("Error while sending massquote on instrument " + rejectInfo[i].getInstrument() + ". Error is:"
	                     + rejectInfo[i].getErrorReason());
	        }
	    }			
		
		long mqID = massQuoteAck.getMassQuoteIdentifier();
		Strategy strategy = runtime.getStrategy();
		FirstQuoteStatus state = (FirstQuoteStatus) strategy.getState();
		MassQuoteData mqData = state.BatchQuoteMQData.get(mqID);
		if( mqData != null ){
			mqData.EndTime_ns = endTime_ns;
			
			runtime.log("FirstQuote", String.format("MQID=%d,Start=%d,End=%d", mqID, mqData.StartTime_ns, mqData.EndTime_ns) );
			if( state.statisticBatch.AddData( new StatisticData( mqData.StartTime_ns, mqData.EndTime_ns) ) == false ){
				runtime.log(LiquidatorRuntime.LOG_ERROR, "FirstQuote", String.format("Add MQID=%d failed",mqID)  );
			}else{
				if( state.statisticBatch.GetCrtIndex() >= state.statisticBatch.GatBatchCount() ){
					ShowBatchTestResult();
					strategy.setParameter(ACTIVE_PARAMETER, 0);
				}
			}
		}

	}
	
	


}
