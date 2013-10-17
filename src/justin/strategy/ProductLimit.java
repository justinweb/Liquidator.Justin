package justin.strategy;

public class ProductLimit {

	public double UpLimit = 0.0;
	public double DownLimit = 0.0;
	
	public String toString()
	{
		return String.format("UpLimit=%f,DownLimit=%f", UpLimit,DownLimit);
	}
}
