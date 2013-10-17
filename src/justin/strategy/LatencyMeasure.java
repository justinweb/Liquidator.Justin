package justin.strategy;

import com.orcsoftware.liquidator.Instrument;
import com.orcsoftware.liquidator.LiquidatorException;
import com.orcsoftware.liquidator.LiquidatorModule;
import com.orcsoftware.liquidator.MarketData;
import com.orcsoftware.liquidator.Order;
import com.orcsoftware.liquidator.OrderManager;
import com.orcsoftware.liquidator.OrderParameters;
import com.orcsoftware.liquidator.Strategy;

public class LatencyMeasure extends LiquidatorModule {
	
	private static final String INSTRUMENT_PARAMETER = "instrument";
	private static final String PRICE_PARAMETER = "price";
	private static final String LOTS_PARAMETER = "lots";
	private static final String SENDORDER_PARAMETER = "sendOrder";
	
	private class StrategyState
	{
		public Instrument orderInstrument = null;
		public double price = 0.0;
		public long lots = 1;
		public long orderID = 0;
		public long orderStartTime = 0;
		public OrderParameters orderParam = null;
		public double downLimit = 0.0;
		public double upLimit = 0.0;
		public long sendOrders = 0;
		
		public String ToString()
		{
			return "Add state(" + orderInstrument.getFeedcode() + "," + price + "," + lots + ")";
		}
	}
	
	private FileLog fLog = null;
	
	/**
	 * Should be called when the strategy is created.
	 */
	public void onCreate() {
		
		try
		{
			fLog = new FileLog( "/etc/orc/liquidator01/justin/strategy/Justin_LatencyMeasure.txt" );
			fLog.WriteLog("Log start ... >>>> ");
		}
		catch( Exception exp )
		{
			runtime.log(exp.toString());
		}
		
		Strategy strategy = runtime.getStrategy();
		// get parameters 
		Instrument orderInstrument = strategy.getInstrumentParameter(INSTRUMENT_PARAMETER);
		
		// save parameters into state
		StrategyState state = new StrategyState();
		state.orderInstrument = orderInstrument;
		state.orderParam = new OrderParameters();
		state.orderParam.setAdditionalData("AC", "0000000");
		state.orderParam.setAdditionalData("M2", "070KV");
		strategy.setState(state);
		
		GetParameters();
		
		// MarketData must be subscribed from onCreate()
		if( state.orderInstrument != null )
			state.orderInstrument.marketDataSubscribe();
		else
			throw new LiquidatorException("Order Instrument is null");
		
		runtime.log( state.ToString() );
		runtime.log("Create end");
	}
	
	/*
	 * call when strategy is uninit
	 */
	public void OnDelete()
	{
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
	
	/*
	 * start
	 */
	public void onStart()
	{
		runtime.log("Start");
		
		Strategy strategy = runtime.getStrategy();
		StrategyState state = (StrategyState)strategy.getState();
		runtime.log( state.ToString() );		

		try
		{
		// special value is from OP_sec.pdf p.91
			MarketData md = state.orderInstrument.getMarketData();
			if( md == null ) runtime.log("MD is null");
			else
			{
				//state.upLimit = (double)md.getMarketSpecificValue("upper_limit");
				//state.downLimit = (double)md.getMarketSpecificValue("lower_limit");
			}
		}
		catch( Exception exp)
		{
			runtime.log(exp.toString());
		}
	}
	
	/*
	 * stop
	 */
	public void onStop()
	{
		runtime.log("Stop");
		for (Order order : runtime.getStrategy().getOrders()) {
			order.remove();
		}
	}

	/*
	 * notified when market data is changed
	 */
	public void onMarketDataIn()
	{
		Strategy strategy = runtime.getStrategy();
		StrategyState state = (StrategyState)strategy.getState();
		if( state.orderInstrument != null  && state.orderID == 0 )
		{
			MarketData targetMD = state.orderInstrument.getMarketData(true);
			runtime.log( state.orderInstrument.getFeedcode() + "," + targetMD.getLast() + ",Bid=" + targetMD.getBid() + ",Ask=" + targetMD.getAsk()  );
			
			// Send order and get returned order id		
			if( state.sendOrders > 0)
				SendOrder( state, targetMD.getBid());
		}
	}
	
	/*
	 * called when order is sent 
	 */
	public void onOrderReport()
	{
		Strategy strategy = runtime.getStrategy(); 
		StrategyState state = (StrategyState)strategy.getState();
		
		runtime.log("Order report...");
		
		if( state.orderID != 0 )
		{
			long orderReportTime = System.nanoTime();
			String mktID = runtime.getOrderManager().getOrder(state.orderInstrument.getMarketName(), state.orderID).getMarketOrderIdentifier();
			runtime.log( "MarketID=" + mktID );
			runtime.log( "Order report duration = " + (orderReportTime - state.orderStartTime) / 1000000.0 + " ms");
			
			try
			{
				fLog.WriteLog( "Symbol=" + state.orderInstrument.getFeedcode() + ",orderID=" + state.orderID + ",MarketID=" + mktID + ",Duration=" + (orderReportTime - state.orderStartTime) / 1000000.0 + " ms");
			}
			catch( Exception exp )
			{
				runtime.log( exp.toString());
			}
			
			runtime.log("sendOrder=" + state.sendOrders);
			
			//runtime.getOrderManager().getOrder(state.orderInstrument.getMarketName(), state.orderID).remove();
			
			state.orderID = 0;
			state.orderStartTime = 0;
			
			
		}
	}
	
	public void onParameterChanged()
	{
		runtime.log( "Parameter changed ");
		 
		GetParameters(); 
	}
	
	private void GetParameters()
	{
		try
		{
			Strategy strategy = runtime.getStrategy();
			StrategyState state = (StrategyState) strategy.getState();

			double price = strategy.getDoubleParameter(PRICE_PARAMETER);
			long lots = strategy.getLongParameter(LOTS_PARAMETER);
			long sendOrder = strategy.getLongParameter(SENDORDER_PARAMETER);

			state.price = price;
			state.lots = lots;

			if (sendOrder > 0)
				state.sendOrders = sendOrder;
		}
		catch(Exception exp)
		{
			runtime.log(exp.toString());
		}
	}
	
	/*
	 * send order
	 */
	private long SendOrder( StrategyState state, double price )
	{
		OrderManager om = runtime.getOrderManager();
		long orderID = om.bid(state.orderInstrument, state.lots, price, state.orderParam);
		if( orderID == 0 )
		{
			runtime.log( "send order failed ");			
		}
		else
		{
			state.orderID = orderID;
			state.orderStartTime = System.nanoTime();
			state.sendOrders -= 1;
			runtime.log( "order sent with ID =" + orderID);		
			runtime.log("2 sendOrder=" + state.sendOrders);
			
			try
			{
				fLog.WriteLog( "Send order orderID=" + orderID);
			}
			catch( Exception exp )
			{
				runtime.log(exp.toString());
			}
			
			runtime.getStrategy().setParameter(SENDORDER_PARAMETER, state.sendOrders);
		}
		
		return orderID;
	}
}
