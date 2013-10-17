package justin.strategy;

import java.util.HashMap;

public class ProductInfo {
	
	private HashMap<String,ProductLimit> dicLimit = new HashMap<String,ProductLimit>();
	
	public ProductLimit getProduct( String symbol )
	{
		if( dicLimit.containsKey(symbol))
			return dicLimit.get(symbol);
		else
			return null;
	}
	
	public void setProduct( String symbol, ProductLimit limit )
	{
		dicLimit.put(symbol, limit);		
	}
}
