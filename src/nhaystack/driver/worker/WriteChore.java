package nhaystack.driver.worker;

import java.net.*;

import nhaystack.driver.*;
import nhaystack.driver.point.*;
import nhaystack.worker.*;

/**
  * WriteChore
  */
public class WriteChore extends WorkerChore
{
    public WriteChore(BNHaystackServer server, BNHaystackProxyExt ext)
    {
        super(
            server.getWorker(),
            "WriteChore:" + 
            server.getApiUrl() + ":" + 
            ext.getId());

        this.server = server;
        this.ext = ext;
    }

    public void doRun() throws Exception
    {
        if (server.isDisabled() || server.isDown() || server.isFault())
            return;

        ext.doWrite();
    }

    public boolean merge(WorkerChore chore)
    {
        return false;
    }

    public boolean isPing() { return false; }

    private final BNHaystackServer server;
    private final BNHaystackProxyExt ext;
}
