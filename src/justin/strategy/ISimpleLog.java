package justin.strategy;

import java.io.IOException;

public interface ISimpleLog {

	void Close() throws IOException;
	void WriteLog( String msg ) throws IOException;
}
