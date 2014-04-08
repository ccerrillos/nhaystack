package nhaystack.driver.point;

import javax.baja.driver.point.*;
import javax.baja.sys.*;

import nhaystack.driver.*;

public class BNHaystackPointFolder extends BPointFolder 
{
    /*-
    class BNHaystackPointFolder
    {
        properties
        {
        }
    }
    -*/
/*+ ------------ BEGIN BAJA AUTO GENERATED CODE ------------ +*/
/*@ $nhaystack.driver.point.BNHaystackPointFolder(3836034387)1.0$ @*/
/* Generated Mon Apr 07 08:34:06 EDT 2014 by Slot-o-Matic 2000 (c) Tridium, Inc. 2000 */

////////////////////////////////////////////////////////////////
// Type
////////////////////////////////////////////////////////////////
  
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BNHaystackPointFolder.class);

/*+ ------------ END BAJA AUTO GENERATED CODE -------------- +*/

    public final BNHaystackNetwork getNHaystackNetwork()
    {
        return (BNHaystackNetwork)getNetwork();
    }

    public final BNHaystackServer getNHaystackServer()
    {
        return (BNHaystackServer) getDevice();
    }

    public boolean isParentLegal(BComponent comp)
    {
        return 
            (comp instanceof BNHaystackPointDeviceExt) ||
            (comp instanceof BNHaystackPointFolder);
    }
}
