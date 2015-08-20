package justin.strategy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import com.orcsoftware.liquidator.Future;
import com.orcsoftware.liquidator.Instrument;
import com.orcsoftware.liquidator.InstrumentFilter;
import com.orcsoftware.liquidator.InstrumentKind;
import com.orcsoftware.liquidator.LiquidatorException;
import com.orcsoftware.liquidator.LiquidatorModule;
import com.orcsoftware.liquidator.MarketData;
import com.orcsoftware.liquidator.Option;
import com.orcsoftware.liquidator.Order;
import com.orcsoftware.liquidator.OrderManager;
import com.orcsoftware.liquidator.OrderParameters;
import com.orcsoftware.liquidator.OrderStatus;
import com.orcsoftware.liquidator.Strategy;

public class LatencyBatchOrder extends LiquidatorModule implements Runnable {
	
	private static LatencyBatchOrder orderSender = new LatencyBatchOrder();
	private BatchOrderState threadParam = null;
	private static Thread thread;
	private static HashMap<String,ProductLimit> dicLimit = new HashMap<String,ProductLimit>();
	
	private FileLog fLog = null;
	
	private static final String INSTRUMENT_PARAMETER = "instrument";
	private static final String PRICE_PARAMETER = "price";
	private static final String LOTS_PARAMETER = "lots";
	private static final String BATCHCOUNT_PARAMETER = "batchCount";
	private static final String ACTIVE_PARAMETER = "active";
	private static final String ORDERMODE_PARAMETER = "orderMode";
	private static final String SIMULATION_PARAMETER = "simulation";
	private static final String SIDE_PARAMETER = "side";
	private static final String TRADERID_PARAMETER = "traderid";
	private static final String ORDERPARAM_TRADERID = "M2";
	private static final String ORDERPARAM_ACCOUNT = "AC";
	private static final String SELSYMBOL_PARAMETER = "selsymbol";
	private static final String DELAY_PARAMETER	= "Delay";
	private static final int 	OrderMode_Batch 	= 1;
	private static final int 	OrderMode_Series 	= 2;
	private static final int 	OrderSide_Bid 	= 1;
	private static final int 	OrderSide_Ask 	= 2;
	private static final String VERSION	= "20150805.1";
	
	private class BatchOrderState
	{
		public Instrument Product = null;
		public long Lots = 1;
		public double Price = 1.0;
		public long BatchCount = 0;
		public OrderParameters OrderParam = null;
		public long OrderMode = 1;
		public Set<Instrument> SeriesProduct = null;
		public long IsSimulation = 1;
		public long Side = 1;
		public long DelayTimeMS = 0;		// delay time between every orders
		
		public BatchOrderState()
		{
			OrderParam = new OrderParameters();
			OrderParam.setAdditionalData(ORDERPARAM_ACCOUNT, "0000000");
			OrderParam.setAdditionalData(ORDERPARAM_TRADERID, "070QAA");	
		}
		
		public String toString()
		{
			String traderID = OrderParam.getAdditionalData(ORDERPARAM_TRADERID);
			return String.format("Lots=%d,Price=%f,BatchCount=%d,OrderMode=%d,NumOfSeries=%d,IsSimulation=%d,Side=%d,TraderID=%s",
					Lots,Price,BatchCount,OrderMode,SeriesProduct.size(),IsSimulation,Side,
					traderID == null ? "":traderID
					);
		}
	}
	
	/*
	 * because the InstrumentFilter could not user external parameter, declare a class to let us define the external parameter
	 */
	private class MyFilter implements InstrumentFilter
	{
		public Instrument Target = null;
		
		public MyFilter( Instrument target )
		{
			Target = target;
		}
		
		@Override
		public boolean accept(Instrument instrument) {
			try
			{
				LogSystem.getInstance().WriteLog("check " + instrument.getFeedcode() + ",Kind=" + instrument.getKind());
				if( instrument.getKind() == InstrumentKind.CALL || instrument.getKind() == InstrumentKind.PUT )
				{	
					Option opt = (Option)instrument;
					Option optSel = (Option)Target;
					if( 
							opt.getBaseInstrument() != null &&
							//opt.getStrikePrice() == optSel.getStrikePrice() &&
							opt.getUnderlyingName().equals( optSel.getUnderlyingName()) &&
							opt.getBaseInstrument().equals(optSel.getBaseInstrument()) &&							
							opt.getExpiry().equals(optSel.getExpiry()))
					{		
						//runtime.log("Add " + instrument.getFeedcode());
						LogSystem.getInstance().WriteLog("Add " + instrument.getFeedcode());						
						return true;
					}
					return false;
				}				
				return false;
			}
			catch( Exception exp)
			{
				runtime.log(exp.toString());
				return false;
			}
		}
	}

	public void onCreate() {
		Strategy strategy = runtime.getStrategy();
		
		try
		{			
			java.util.Date crtDate = new java.util.Date();
			java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat();
			dateFormat.applyPattern("yyyyMMdd_HHmmss");
			String logName = "/orcreleases/logs/Justin_LatencyBatchOrder_" + dateFormat.format(crtDate) + ".txt";
			
			fLog = new FileLog( logName );
			LogSystem.getInstance().AttachLog(fLog);
			fLog.WriteLog("Log start ... >>>> ");
			fLog.WriteLog("Version:" + VERSION );
		}
		catch( Exception exp )
		{
			runtime.log(exp.toString());
			runtime.log("Craete and set log failed");
		}
		
		// get parameters
		BatchOrderState state = new BatchOrderState();
		state.Product = strategy.getInstrumentParameter(INSTRUMENT_PARAMETER);
		
		// find product series
		//state.SeriesProduct = runtime.getInstrumentManager().findInstruments(
		//		state.Product.getMarketName(), new MyFilter( state.Product) );
		final Instrument target = state.Product; // must be final to be used in InstrumentFilter
		try {
			LogSystem.getInstance().WriteLog( "Underlying = " + target.getUnderlying());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		state.SeriesProduct = runtime.getInstrumentManager().findInstruments(state.Product.getMarketName(),
				new InstrumentFilter(){
					@Override
					public boolean accept(Instrument instrument) {
						try
						{
							LogSystem.getInstance().WriteLog("check " + instrument.getFeedcode() + ",Kind=" + instrument.getKind() + "," + instrument.getUnderlying());
							InstrumentKind targetKind = target.getKind(); 
							if( targetKind == InstrumentKind.FUTURE)
							{
								if( instrument.getKind() == InstrumentKind.FUTURE )
								{
									Future fut = (Future)instrument;
									Future futSel = (Future)target;
									return( fut.getUnderlying().equals(futSel.getUnderlying()));										
								}								
							}
							else if( targetKind == InstrumentKind.CALL || targetKind == InstrumentKind.PUT)
							{
								if( instrument.getKind() == InstrumentKind.CALL || instrument.getKind() == InstrumentKind.PUT )
								{	
									Option opt = (Option)instrument;
									Option optSel = (Option)target;
									if( 
											opt.getBaseInstrument() != null &&
											//opt.getStrikePrice() == optSel.getStrikePrice() &&
											opt.getUnderlyingName().equals( optSel.getUnderlyingName()) &&
											opt.getBaseInstrument().equals(optSel.getBaseInstrument()) &&							
											opt.getExpiry().equals(optSel.getExpiry()))
									{		
										//runtime.log("Add " + instrument.getFeedcode());
										LogSystem.getInstance().WriteLog("Add " + instrument.getFeedcode());						
										return true;
									}
									return false;
								}			
							}
							return false;
						}
						catch( Exception exp)
						{
							runtime.log(exp.toString());
							return false;
						}
					}
				});
		runtime.log("Total find " + state.SeriesProduct.size());
		
		strategy.setState(state);
		try {
			LogSystem.getInstance().WriteLog("Set State," + state.toString());
		} catch (IOException exp) {
			// TODO Auto-generated catch block
			runtime.log(exp.toString());
		}
		
		state.Product.marketDataSubscribe();
		for( Instrument orderProduct : state.SeriesProduct)
		{
			orderProduct.marketDataSubscribe();
		}
		
		GetParameters();
		
		// show selected instrument
		strategy.setParameter(SELSYMBOL_PARAMETER, state.Product.getFeedcode() );
		
	}
	
	public void onDelete(){
		
		Strategy strategy = runtime.getStrategy();
		BatchOrderState state = (BatchOrderState)strategy.getState();
		
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
		BatchOrderState state = (BatchOrderState)strategy.getState();
		
		// update parameters
		GetParameters();
		
		ProductLimit limit = getProductLimit( state.Product );
		if( limit != null )
			dicLimit.put(state.Product.getFeedcode(), limit);
		
		for( Instrument orderProduct : state.SeriesProduct)
		{
			limit = getProductLimit( orderProduct );
			if( limit != null )
				dicLimit.put(orderProduct.getFeedcode(), limit);
		}		
		
		// dump limit information
		for( String s : dicLimit.keySet())
		{
			limit = dicLimit.get(s);
			try {
				LogSystem.getInstance().WriteLog( String.format("Symbol=%s,%s", s, limit.toString() ));
			} catch (IOException e) {
				runtime.log(e.toString());
			}
		}
		Iterator<String> symbols = dicLimit.keySet().iterator();
		while( symbols.hasNext())
		{
			String symbol = symbols.next(); 
			limit = dicLimit.get(symbol);
			try {
				LogSystem.getInstance().WriteLog( String.format("Symbol=%s,%s", symbol, limit.toString() ));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public void onStop(){
		runtime.log("Stop");
		for (Order order : runtime.getStrategy().getOrders()) {
			order.remove();
		}
	}
	
	public void onParameterChanged(){		
		
		runtime.log( "Parameter changed ");		 
		GetParameters(); 
		
		Strategy strategy = runtime.getStrategy();
		BatchOrderState state = (BatchOrderState) strategy.getState();
		long isActive = strategy.getLongParameter(ACTIVE_PARAMETER);		
		if( isActive > 0 && thread == null )
		{
			// start thread to send orders
			orderSender.threadParam = state;
//			thread = new Thread(orderSender);
//			thread.setPriority(Thread.MIN_PRIORITY);
//			thread.start();
			runtime.log("Start order sending thread");
			
			run();
		}	
	}
	
	public void onOrderStatusChange(Order order) {
		OrderStatus status = order.getStatus();
		
		switch (status) {
		
		case ACTIVE:
			if( fLog != null)
			{
				try
				{					
					fLog.WriteLog(String.format("$$>>,OrderID=%d,MarketID=%s,Symbol=%s", order.getOrderIdentifier()
							,order.getMarketOrderIdentifier()
							,order.getInstrument().getFeedcode())
							);
				}
				catch(Exception exp)
				{
					runtime.log(exp.toString());
				}
			}
			break;
		case DELETED:
			// order is filled when it is sent will receive 'DELETED' message
			if( fLog != null)
			{
				try
				{					
					fLog.WriteLog(String.format("$$>>,OrderID=%d,MarketID=%s,Symbol=%s", order.getOrderIdentifier()
							,order.getMarketOrderIdentifier()
							,order.getInstrument().getFeedcode())
							);
				}
				catch(Exception exp)
				{
					runtime.log(exp.toString());
				}
			}
			
			runtime.log(String
					.format("Order status changed for order:  OrderId [%d] Instrument [%s]  Status [%s]  Reason [%s] Volume [%f]  Deleted volume [%f]   Filled volume [%f]",
							Long.valueOf(order.getOrderIdentifier()), order.getInstrument(), status,
							order.getErrorReason(), order.getVolume(), order.getDeletedVolume(),
							order.getFilledVolume()));
			break;
		case PENDING_ADD:
		case PENDING_AMEND:
		case PENDING_DELETE:
			runtime.log("Order is pending: " + order.getOrderIdentifier() + " on contract " + order.getInstrument()
					+ ". Status is :" + status);
			break;
		case ERROR_ADD:
		case ERROR_DELETE:
			runtime.log("Error while inserting/deleting order: " + order.getOrderIdentifier() + ". Error is: "
					+ order.getErrorReason());
			throw new LiquidatorException("Error while inserting/deleting order: " + order.getOrderIdentifier()
					+ ". Error is: " + order.getErrorReason());
		case OFFLINE:
		case UNKNOWN:
			// Nothing to do, should not happen
			break;
		default:
			break;
		
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
			Strategy strategy = runtime.getStrategy();
			BatchOrderState state = (BatchOrderState) strategy.getState();

			double price = strategy.getDoubleParameter(PRICE_PARAMETER);
			long lots = strategy.getLongParameter(LOTS_PARAMETER);
			long batchCount = strategy.getLongParameter(BATCHCOUNT_PARAMETER);			
			long orderMode = strategy.getLongParameter(ORDERMODE_PARAMETER);
			long isSimulation = strategy.getLongParameter(SIMULATION_PARAMETER);
			long side = strategy.getLongParameter(SIDE_PARAMETER);
			long delayTimeMS = strategy.getLongParameter(DELAY_PARAMETER);
			String traderID = (String)strategy.getParameter(TRADERID_PARAMETER);

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
			if( orderMode >= 1 && orderMode <= 2)
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
			if( delayTimeMS >= 0 ){
				state.DelayTimeMS = delayTimeMS;				
				LogSystem.getInstance().WriteLog("Set DelayTimeMS = " + state.DelayTimeMS );
			}
			
			LogSystem.getInstance().WriteLog("Update State," + state.toString());			
		}
		catch(Exception exp)
		{
			runtime.log(exp.toString());
		}
	}
	
	@Override
	public void run() {		
		
		try {
			
			//LogSystem.getInstance().WriteLog("[Thread] start ...");
			
			if( orderSender.threadParam == null )
			{
				runtime.log( "[Thread] parameter is null");
				return;
			}
			
			Strategy strategy = runtime.getStrategy();			
			BatchOrderState state = orderSender.threadParam;
			switch( (int)state.OrderMode )
			{
			case OrderMode_Batch:
				RunBatchMode(state);
				break;
			case OrderMode_Series:
				RunSeriesMode(state);
				break;
			default:
				runtime.log("Unknown OrderMode=" + state.OrderMode);
				break;
			}
			
			//LogSystem.getInstance().WriteLog("[Thread] finished ...");
			
			thread = null;			
			strategy.setParameter(ACTIVE_PARAMETER, 0);
		} catch (Exception e) {
			runtime.log(e.toString());
		}
	}
	
	private void RunSeriesMode(BatchOrderState state){
		
		OrderManager om = runtime.getOrderManager();
		
		//while (thread != null && !thread.isInterrupted()) {		 
		try	{			
			//LogSystem.getInstance().WriteLog("$$>>,TestMode=Series,Status=Start");
			
			for( Instrument orderProduct : state.SeriesProduct)
			{
				double orderPrice = state.Price;
				// Get limit price
				if( dicLimit.containsKey(orderProduct.getFeedcode()) )
				{
					orderPrice = dicLimit.get(orderProduct.getFeedcode()).DownLimit;
				}
				
				long orderID = SendOrder( om, orderProduct, state.Lots, orderPrice, state.IsSimulation != 1, state.Side, state.OrderParam);

				runtime.log("Send order ID = " + orderID);
				LogSystem.getInstance().WriteLog("Send order ID = " + orderID);
				
			}
			//LogSystem.getInstance().WriteLog("$$>>,TestMode=Series,Status=End");
		}
		catch( Exception exp )
		{
			runtime.log(exp.toString());		
		}
		//}
	}
	
	private void RunBatchMode(BatchOrderState state){
		
		Strategy strategy = runtime.getStrategy();
		OrderManager om = runtime.getOrderManager();
		
		if( strategy == null || om == null )  // 在thread裏拿不到這些資訊
		{
			runtime.log( "Strategy or OrderManager is null");			
		}
		else
		{		
			//while (thread != null && !thread.isInterrupted()) {
				// Do some work here

			try
			{	
				boolean isSimulate = state.IsSimulation != 1;
				long totalRun = state.BatchCount;
				LogSystem.getInstance().WriteLog("$$>>,TestMode=Batch,Status=Start,IsSimulation=" + (isSimulate ? "Yes" : "No") + ",BatchCount=" + totalRun );				
				for (int i = 0; i < totalRun; ++i) {

					//runtime.log("Order#" + i);
					long orderID = SendOrder( om, state.Product, state.Lots, state.Price, isSimulate, state.Side, state.OrderParam);

					//runtime.log("Send order ID = " + orderID);
					//LogSystem.getInstance().WriteLog(
					//		"Send order ID = " + orderID);	
					if( state.DelayTimeMS > 0 )
						Thread.sleep( state.DelayTimeMS ) ;
					
				}
				LogSystem.getInstance().WriteLog("$$>>,TestMode=Batch,Status=End");
			}
			catch( Exception exp )
			{
				runtime.log(exp.toString());
			}
			//}
				
			state.BatchCount = 0;
			strategy.setParameter(BATCHCOUNT_PARAMETER,state.BatchCount);
		}		
	}
	
	private long SendOrder(OrderManager om, Instrument product, long lots, double price, boolean isSimulation, long side, OrderParameters orderParam )
	{
		try {
			//String msg = "";	
			String msg = String.format( "SendOrder OrderBookID(%s),Lots(%d),Price(%f)", product.getFeedcode(), lots, price );
			
			long orderID = -1;
			switch ((int)side) {
			case OrderSide_Bid:
				if (isSimulation)
					orderID = om.bid(product, lots, price, orderParam);
				//msg = String.format(
				//		"Send Order-Bid,Symbol=%s,Lots=%d,Price=%f",product.getFeedcode(), lots, price);
				break;
			case OrderSide_Ask:
				if (isSimulation)
					orderID = om.offer(product, lots, price, orderParam);
				//msg = String.format(
				//		"Send Order-Ask,Symbol=%s,Lots=%d,Price=%f",product.getFeedcode(), lots, price);
				break;
			default:
				runtime.log( "Unknown OrderSide=" + side);
				break;
			}
			
			//runtime.log(msg);
			LogSystem.getInstance().WriteLog(msg);
			
			return orderID;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return -1;
		}
	}
}
