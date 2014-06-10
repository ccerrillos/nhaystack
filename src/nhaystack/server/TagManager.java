//
// Copyright (c) 2012, J2 Innovations
// Licensed under the Academic Free License version 3.0
//
// History:
//   07 Nov 2011  Richard McElhinney  Creation
//   28 Sep 2012  Mike Jarmy          Ported from axhaystack
//
package nhaystack.server;

import java.util.*;
import javax.baja.control.*;
import javax.baja.history.*;
import javax.baja.history.ext.*;
import javax.baja.log.*;
import javax.baja.naming.*;
import javax.baja.status.*;
import javax.baja.sys.*;
import javax.baja.units.*;
import javax.baja.util.*;

import org.projecthaystack.*;
import org.projecthaystack.io.*;
import org.projecthaystack.util.*;

import nhaystack.*;
import nhaystack.res.*;
import nhaystack.site.*;
import nhaystack.util.*;

/**
  * TagManager does various task associated with generating tags
  * and looking things up based on ids, etc.
  */
public class TagManager
{
    TagManager(
        NHServer server,
        BNHaystackService service,
        SpaceManager spaceMgr,
        Cache cache)
    {
        this.server = server;
        this.service = service;
        this.spaceMgr = spaceMgr;
        this.cache = cache;
    }

////////////////////////////////////////////////////////////////
// public
////////////////////////////////////////////////////////////////

    /**
      * Return a copy of the given annotated tags, except convert any
      * Refs from ComponentRefs to SepRefs.
      */
    public HDict convertAnnotatedRefTags(HDict annotatedTags)
    {
        HDictBuilder hdb = new HDictBuilder();

        Iterator it = annotatedTags.iterator();
        while (it.hasNext())
        {
            Map.Entry entry = (Map.Entry) it.next();
            String name = (String) entry.getKey();
            HVal val = (HVal) entry.getValue();

            if (val instanceof HRef)
                hdb.add(name, convertAnnotatedRefTag((HRef) val));
            else
                hdb.add(name, val);
        }

        return hdb.toDict();
    }

    /**
      * Look up the a BComponent by its HRef id.
      *
      * Return null if the BComponent cannot be found,
      * or if it is not haystack-annotated.
      */
    public BComponent lookupComponent(HRef id)
    {
        NHRef nh = NHRef.make(id);

        // component space
        if (nh.getSpace().equals(NHRef.COMP) ||
            nh.getSpace().equals(NHRef.COMP_BASE64))
        {
            BOrd ord = BOrd.make("station:|" + 
                (nh.getSpace().equals(NHRef.COMP) ? 
                    "slot:" + PathUtil.toNiagaraPath(nh.getPath()) : 
                    Base64.URI.decodeUTF8(nh.getPath())));

            BComponent comp = (BComponent) ord.get(service, null);
            if (comp == null) return null;
            return spaceMgr.isVisibleComponent(comp) ? comp : null;
        }
        // history space
        else if (
            nh.getSpace().equals(NHRef.HIS_BASE64) ||
            nh.getSpace().equals(NHRef.HIS))
        {
            BHistoryId hid = BHistoryId.make(
                nh.getSpace().equals(NHRef.HIS) ? 
                    "/" + PathUtil.toNiagaraPath(nh.getPath()) :
                    Base64.URI.decodeUTF8(nh.getPath()));

            BIHistory history = service.getHistoryDb().getHistory(hid);
            BHistoryConfig cfg = history.getConfig();
            return spaceMgr.isVisibleHistory(cfg) ? cfg : null;
        }
        // sep space
        else if (nh.getSpace().equals(NHRef.SEP))
        {
            return cache.lookupComponentBySepRef(nh);
        }
        // invalid space
        else 
        {
            return null;
        }
    }

    /**
      * Make an ID from a BComponent.  
      */
    public NHRef makeComponentRef(BComponent comp)
    {
        // history space
        if (comp instanceof BHistoryConfig)
        {
            BHistoryConfig cfg = (BHistoryConfig) comp;
            return makeHistoryRef(cfg);
        }
        // component space
        else
        {
            // sepRefs are cached so we don't have to make them on the fly
            NHRef sepRef = cache.lookupSepRefByComponent(comp);
            if (sepRef != null) return sepRef;

            return makeSlotPathRef(comp);
        }
    }

    public static NHRef makeSlotPathRef(BComponent comp)
    {
        String path = comp.getSlotPath().toString();
        path = removePrefix(path, "slot:/");
        path = PathUtil.fromNiagaraPath(path);

        return NHRef.make(NHRef.COMP, path);
    }

    public static NHRef makeSepRef(String[] navPath)
    {
        return NHRef.make(
            NHRef.SEP, 
            PathUtil.fromNiagaraPath(
                TextUtil.join(navPath, '/')));
    }

////////////////////////////////////////////////////////////////
// package-scope
////////////////////////////////////////////////////////////////

    /**
      * Create the haystack representation of a BComponent.
      *
      * The haystack representation is a combination of the 
      * autogenerated tags, and those tags specified
      * in the explicit haystack annotation (if any).
      *
      * This method never returns null.
      */
    HDict createTags(BComponent comp)
    {
        return (comp instanceof BHistoryConfig) ?
            createHistoryTags((BHistoryConfig) comp) :
            createComponentTags(comp);
    }

    /**
      * look up the BHistoryConfig for a HRef
      */
    BHistoryConfig lookupHistoryConfig(HRef id)
    {
        BComponent comp = lookupComponent(id);
        if (comp == null)
        {
            LOG.error("lookup failed for '" + id + "'");
            return null;
        }

        // history space
        if (comp instanceof BHistoryConfig)
        {
            BHistoryConfig cfg = (BHistoryConfig) comp;
            return spaceMgr.isVisibleHistory(cfg) ? 
                cfg : null;
        }
        // component space
        else if (comp instanceof BControlPoint)
        {
            return spaceMgr.lookupHistoryFromPoint((BControlPoint) comp);
        }
        else
        {
            LOG.error("cannot find history for for '" + id + "'");
            return null;
        }
    }

    /**
      * Create the haystack representation of a BComponent.
      *
      * The haystack representation is a combination of the 
      * autogenerated tags, and those tags specified
      * in the explicit haystack annotation (if any).
      *
      * This method never returns null.
      */
    HDict createComponentTags(BComponent comp)
    {
        HDictBuilder hdb = new HDictBuilder();

        if (comp instanceof BHTagged)
        {
            hdb.add(((BHTagged) comp).generateTags(server));
        }
        else
        {
            // add existing tags
            HDict tags = BHDict.findTagAnnotation(comp);
            if (tags == null) 
                tags = HDict.EMPTY;
            else
                hdb.add("axAnnotated");

            hdb.add(convertAnnotatedRefTags(tags));

            // navName
            String navName = Nav.makeNavName(comp, tags);
            hdb.add("navName", navName);

            // add misc other tags
            hdb.add("axType", comp.getType().toString());
            hdb.add("axSlotPath", comp.getSlotPath().toString());

            // points get special treatment
            if (comp instanceof BControlPoint)
                createPointTags((BControlPoint) comp, hdb, tags);

            // dis
            String dis = createDis(comp, tags, hdb);
            hdb.add("dis", dis);

            // add id
            HRef id = makeComponentRef(comp).getHRef();
            hdb.add("id", HRef.make(id.val, dis));
        }

        // add custom tags
        hdb.add(server.createCustomTags(comp));

        // done
        return hdb.toDict();
    }

    /**
      * Create the tags which represent a change-of-value on a point.
      * The only tags that are returned are id, curVal, and curStatus.
      */
    HDict createPointCovTags(BControlPoint point)
    {
        HDictBuilder hdb = new HDictBuilder();

        // id
        HRef id = makeComponentRef(point).getHRef();
        hdb.add("id", id);

        // curVal
        HDict tags = BHDict.findTagAnnotation(point);
        if (tags == null) tags = HDict.EMPTY;
        HVal curVal = makeCurVal(point, tags);
        hdb.add("curVal", curVal);

        // curStatus
        BStatus status = point.getStatus();
        HStr curStatus = makeCurStatus(status);
        hdb.add("curStatus", curStatus);

        // ax status tags
        addAxStatusTags(hdb, status);

        // done
        return hdb.toDict();
    }

    /**
      * Create the haystack representation of a BHistoryConfig.
      *
      * The haystack representation is a combination of the 
      * autogenerated tags, and those tags specified
      * in the explicit haystack annotation (if any).
      *
      * This method never returns null.
      */
    HDict createHistoryTags(BHistoryConfig cfg)
    {
        HDictBuilder hdb = new HDictBuilder();

        // add existing tags
        HDict tags = BHDict.findTagAnnotation(cfg);
        if (tags == null) 
            tags = HDict.EMPTY;
        hdb.add(tags);

        // add dis
        String dis = cfg.getId().toString();
        if (dis.startsWith("/")) dis = dis.substring(1);
        dis = TextUtil.replace(dis, "/", "_");
        hdb.add("dis", dis);
        hdb.add("navName", dis);

        // add id
        HRef ref = makeComponentRef(cfg).getHRef();
        hdb.add("id", HRef.make(ref.val, dis));

        // add misc other tags
        hdb.add("axType", cfg.getType().toString());
        hdb.add("axHistoryId", cfg.getId().toString());

        hdb.add("point");
        hdb.add("his");

        // time zone
        if (!tags.has("tz"))
        {
            HTimeZone tz = server.fromBajaTimeZone(cfg.getTimeZone());
            if (tz != null) hdb.add("tz", tz.name);
        }

        // point kind tags
        Type recType = cfg.getRecordType().getResolvedType();
        if (recType.is(BTrendRecord.TYPE))
        {
            int pointKind = getTrendRecordKind(recType);
            BFacets facets = (BFacets) cfg.get("valueFacets");
            addPointKindTags(pointKind, facets, tags, hdb);
        }
        // if its not a BTrendRecord, just say that the kind is Str
        else
        {
            if (!tags.has("kind")) hdb.add("kind", "Str");
        }

        // check if this history has a point
        BControlPoint point = spaceMgr.lookupPointFromHistory(cfg);
        if (point != null)
        {
            // add point ref
            hdb.add("axPointRef", makeComponentRef(point).getHRef());

            // hisInterpolate 
            if (!tags.has("hisInterpolate"))
            {
                BHistoryExt historyExt = spaceMgr.lookupHistoryExt(point);
                if (historyExt != null && (historyExt instanceof BCovHistoryExt))
                    hdb.add("hisInterpolate", "cov");
            }
        }

        // add custom tags
        hdb.add(server.createCustomTags(cfg));

        // done
        return hdb.toDict();
    }

////////////////////////////////////////////////////////////////
// private
////////////////////////////////////////////////////////////////

    /**
      * Convert the ref from ComponentRef to SepRef (unless there is no SepRef).
      */
    private HRef convertAnnotatedRefTag(HRef ref)
    {
        BComponent comp = lookupComponent(ref);
        return makeComponentRef(comp).getHRef();
    }

    /**
      * create the 'dis' tag
      */
    private String createDis(BComponent comp, HDict tags, HDictBuilder hdb)
    {
        String dis = makeDisName(comp, tags);

        if (hdb.has("point"))
        {
            String equipDis = lookupDisName(hdb, "equipRef");
            if (equipDis != null)
            {
                dis = equipDis + " " + dis;

                String siteDis = lookupDisName(hdb, "siteRef");
                if (siteDis != null)
                    dis = siteDis + " " + equipDis + " " + dis;
            }
        }
        else if (hdb.has("equip"))
        {
            String siteDis = lookupDisName(hdb, "siteRef");
            if (siteDis != null)
                dis = siteDis + " " + dis;
        }

        return dis;
    }

    /**
      * look up the 'dis' associated with the given tag name
      */
    private String lookupDisName(HDictBuilder hdb, String tagName)
    {
        if (hdb.has(tagName))
        {
            BComponent comp = lookupComponent((HRef) hdb.get(tagName));
            if (comp != null)
            {
                HDict compTags = BHDict.findTagAnnotation(comp);
                return makeDisName(comp, compTags);
            }
        }
        return null;
    }

    /**
      * create all the point-specific tags
      */
    private void createPointTags(
        BControlPoint point, 
        HDictBuilder hdb,
        HDict tags)
    {
        // ensure there is a point marker tag
        hdb.add("point");

        // check if this point has a history
        BHistoryConfig cfg = spaceMgr.lookupHistoryFromPoint(point);
        if (cfg != null)
        {
            hdb.add("his");

            if (service.getShowLinkedHistories())
                hdb.add("axHistoryRef", makeComponentRef(cfg).getHRef());

            // tz
            if (!tags.has("tz"))
            {
                HTimeZone tz = server.fromBajaTimeZone(cfg.getTimeZone());
                if (tz != null) hdb.add("tz", tz.name);
            }

            // hisInterpolate 
            if (!tags.has("hisInterpolate"))
            {
                BHistoryExt historyExt = spaceMgr.lookupHistoryExt(point);
                if (historyExt != null && (historyExt instanceof BCovHistoryExt))
                    hdb.add("hisInterpolate", "cov");
            }
        }

        // point kind tags
        int pointKind = getControlPointKind(point);
        BFacets facets = (BFacets) point.get("facets");
        addPointKindTags(pointKind, facets, tags, hdb);

        // cur, writable
        hdb.add("cur");
        if (point.isWritablePoint())
            hdb.add("writable");

        // curVal
        HVal curVal = makeCurVal(point, tags);
        if (curVal != null) hdb.add("curVal", curVal);

        // curStatus
        BStatus status = point.getStatus();
        HStr curStatus = makeCurStatus(status);
        if (curStatus != null) hdb.add("curStatus", curStatus);

        // minVal, maxVal, precision
        BNumber minVal    = getNumberFacet(facets, BFacets.MIN);
        BNumber maxVal    = getNumberFacet(facets, BFacets.MAX);
        BNumber precision = getNumberFacet(facets, BFacets.PRECISION);
        if (minVal    != null) hdb.add("minVal",    HNum.make(minVal.getInt()));
        if (maxVal    != null) hdb.add("maxVal",    HNum.make(maxVal.getInt()));
        if (precision != null) hdb.add("precision", HNum.make(precision.getInt()));

        // ax status tags
        addAxStatusTags(hdb, status);

        // actions tag
        if (point.isWritablePoint())
        {
            HGrid actionsGrid = createPointActions(point, pointKind);
            if (actionsGrid != null)
                hdb.add("actions", HStr.make(HZincWriter.gridToString(actionsGrid)));
        }

        // the point is explicitly tagged with an equipRef
        if (tags.has("equipRef"))
        {
            BComponent equip = lookupComponent((HRef) tags.get("equipRef"));

            // try to look up  siteRef too
            HDict equipTags = BHDict.findTagAnnotation(equip);
            if (equipTags.has("siteRef"))
                hdb.add(
                    "siteRef", 
                    convertAnnotatedRefTag(equipTags.getRef("siteRef")));
        }
        // maybe we've cached an implicit equipRef
        else
        {
            BComponent equip = server.getCache().getImplicitEquip(point);
            if (equip != null)
            {
                hdb.add("equipRef", makeComponentRef(equip).getHRef());

                // try to look up  siteRef too
                HDict equipTags = BHDict.findTagAnnotation(equip);
                if (equipTags.has("siteRef"))
                    hdb.add(
                        "siteRef", 
                        convertAnnotatedRefTag(equipTags.getRef("siteRef")));
            }
        }
    }

    private BNumber getNumberFacet(BFacets facets, String name)
    {
        BNumber num = (BNumber) facets.get(name);
        if (num == null) return null;
        if (num.toString().equals("+inf")) return null;
        if (num.toString().equals("-inf")) return null;
        return num;
    }

    /**
      * create the 'actions' tag for a point
      */
    private static HGrid createPointActions(BControlPoint point, int pointKind)
    {
        Array arr = new Array(HDict.class);

        switch(pointKind)
        {
            case NUMERIC_KIND:
            case ENUM_KIND:
            case STRING_KIND:
                addPointAction(point, arr, "override",          "pointOverride($self, $val, $duration)");
                addPointAction(point, arr, "auto",              "pointAuto($self)");
                addPointAction(point, arr, "emergencyOverride", "pointEmergencyOverride($self, $val)");
                addPointAction(point, arr, "emergencyAuto",     "pointEmergencyAuto($self)");
                break;

            case BOOLEAN_KIND:
                addPointAction(point, arr, "active",   "pointOverride($self, true, $duration)");
                addPointAction(point, arr, "inactive", "pointOverride($self, false, $duration)");
                addPointAction(point, arr, "auto",     "pointAuto($self)");

                addPointAction(point, arr, "emergencyActive",   "pointEmergencyOverride($self, true, $duration)");
                addPointAction(point, arr, "emergencyInactive", "pointEmergencyOverride($self, false, $duration)");
                addPointAction(point, arr, "emergencyAuto",     "pointEmergencyAuto($self)");
                break;
        }

        HDict[] rows = (HDict[]) arr.trim();
        return (rows.length == 0) ?
            null : HGridBuilder.dictsToGrid(rows);
    }

    /**
      * add an action for the point (unless its hidden)
      */
    private static void addPointAction(
        BControlPoint point,
        Array arr,
        String name,
        String expr)
    {
        Action action = point.getAction(name);
        if (action == null) return;
        if (Flags.isHidden(point, action)) return;

        HDictBuilder hdb = new HDictBuilder();
        hdb.add("dis", point.getDisplayName(action, null));
        hdb.add("expr", expr);

        arr.add(hdb.toDict());
    }

    private static NHRef makeHistoryRef(BHistoryConfig cfg)
    {
        String path = cfg.getId().toString();
        path = removePrefix(path, "/");
        path = PathUtil.fromNiagaraPath(path);

        return NHRef.make(NHRef.HIS, path);
    }

    private static String removePrefix(String path, String prefix)
    {
        if (!path.startsWith(prefix))
            throw new IllegalStateException(
                "'" + path + "' does not start with '" + prefix + "'");

        return path.substring(prefix.length());
    }

    /**
      * make 'dis' based on navNameFormat
      */
    private static String makeDisName(BComponent comp, HDict tags)
    {
        String format = tags.has("navNameFormat") ?
            tags.getStr("navNameFormat") :
            "%displayName%";
        return BFormat.format(format, comp);
    }

    /**
      * create a curVal for a point
      */
    private static HVal makeCurVal(BControlPoint point, HDict tags)
    {
        switch(getControlPointKind(point))
        {
            case NUMERIC_KIND:
                BNumericPoint np = (BNumericPoint) point;

                if (tags.has("unit"))
                {
                    HVal unit = tags.get("unit");
                    return HNum.make(np.getNumeric(), unit.toString());
                }
                else
                {
                    BFacets facets = (BFacets) point.get("facets");
                    Unit unit = findUnit(facets);
                    if (unit == null) 
                        return HNum.make(np.getNumeric());
                    else
                        return HNum.make(np.getNumeric(), unit.symbol);
                }

            case BOOLEAN_KIND:
                BBooleanPoint bp = (BBooleanPoint) point;
                return HBool.make(bp.getBoolean());

            case ENUM_KIND:
                BEnumPoint ep = (BEnumPoint) point;
                return HStr.make(ep.getEnum().toString());

            case STRING_KIND:
                BStringPoint sp = (BStringPoint) point;
                return HStr.make(sp.getOut().getValue().toString());

            default: 
                return null;
        }
    }

    /**
      * create a curStatus from a BStatus
      */
    private static HStr makeCurStatus(BStatus status)
    {
        if      (status.isOk())       return HStr.make("ok");
        else if (status.isDisabled()) return HStr.make("disabled");
        else if (status.isFault())    return HStr.make("fault");
        else if (status.isDown())     return HStr.make("down");
        else if (status.isNull())     return HStr.make("unknown");

        // these qualify as "ok" for curStatus
        else if (status.isOverridden() ||
                 status.isAlarm() ||
                 status.isStale() ||
                 status.isUnackedAlarm())
        {
            return HStr.make("ok");
        }

        return null;
    }

    /**
      * add all the 'ax' tags associated with a BStatus
      */
    private static void addAxStatusTags(HDictBuilder hdb, BStatus status)
    {
        if (status.isDisabled())     hdb.add("axDisabled");
        if (status.isFault())        hdb.add("axFault");
        if (status.isDown())         hdb.add("axDown");
        if (status.isNull())         hdb.add("axNull");
        if (status.isOverridden())   hdb.add("axOverridden"); 
        if (status.isAlarm())        hdb.add("axAlarm");
        if (status.isStale())        hdb.add("axStale");
        if (status.isUnackedAlarm()) hdb.add("axUnackedAlarm");
    }

    /**
      * add the 'kind' tag, along with an associated tags 
      * like 'enum' or 'unit'
      */
    private static void addPointKindTags(
        int pointKind, 
        BFacets facets, 
        HDict tags, 
        HDictBuilder hdb)
    {
        switch(pointKind)
        {
            case NUMERIC_KIND:

                if (!tags.has("kind")) hdb.add("kind", "Number");

                if (!tags.has("unit"))
                {
                    Unit unit = findUnit(facets);
                    if (unit != null) 
                        hdb.add("unit", unit.symbol);
                }

                break;

            case BOOLEAN_KIND:

                if (!tags.has("kind")) hdb.add("kind", "Bool");
                if (!tags.has("enum")) hdb.add("enum", findTrueFalse(facets));
                break;

            case ENUM_KIND:

                if (!tags.has("kind")) hdb.add("kind", "Str");
                if (!tags.has("enum")) hdb.add("enum", findRange(facets));
                break;

            case STRING_KIND:

                if (!tags.has("kind")) hdb.add("kind", "Str");
                break;
        }
    }

    private static Unit findUnit(BFacets facets)
    {
        if (facets == null) 
            return null;

        BUnit unit = (BUnit)facets.get(BFacets.UNITS);
        if ((unit == null) || (unit.isNull()))
            return null;

        int conv = facets.geti("unitConversion", 0);
        if (conv != 0)
            unit = BUnitConversion.make(conv).getDesiredUnit(unit);

        return Resources.fromBajaUnit(unit);
    }

    private static int getControlPointKind(BControlPoint point)
    {
        if      (point instanceof BNumericPoint) return NUMERIC_KIND;
        else if (point instanceof BBooleanPoint) return BOOLEAN_KIND;
        else if (point instanceof BEnumPoint)    return ENUM_KIND;
        else if (point instanceof BStringPoint)  return STRING_KIND;

        else return UNKNOWN_KIND;
    }

    private static String findTrueFalse(BFacets facets)
    {
        if (facets == null) 
            return "false,true";

        return 
            facets.gets("falseText", "false") + "," +
            facets.gets("trueText", "true");
    }

    private static String findRange(BFacets facets)
    {
        if (facets == null) 
            return "";

        BEnumRange range = (BEnumRange) facets.get("range");
        if ((range == null) || (range.isNull()))
            return "";

        StringBuffer sb = new StringBuffer();
        int[] ord = range.getOrdinals();
        for (int i = 0; i < ord.length; i++)
        {
            if (i > 0) sb.append(",");
            sb.append(range.get(i).getTag());
        }
        return sb.toString();
    }

    private static int getTrendRecordKind(Type trendRecType)
    {
        if      (trendRecType.is(BNumericTrendRecord.TYPE)) return NUMERIC_KIND;
        else if (trendRecType.is(BBooleanTrendRecord.TYPE)) return BOOLEAN_KIND;
        else if (trendRecType.is(BEnumTrendRecord.TYPE))    return ENUM_KIND;
        else if (trendRecType.is(BStringTrendRecord.TYPE))  return STRING_KIND;

        else return UNKNOWN_KIND;
    }

////////////////////////////////////////////////////////////////
// attributes
////////////////////////////////////////////////////////////////

    /** Every single tag which the server may have auto-generated.  */
    static final String[] AUTO_GEN_TAGS = new String[] {

        "axAnnotated",    
        "axHistoryId",    
        "axHistoryRef", 
        "axPointRef",
        "axSlotPath", 
        "axType",         

        "axAlarm",
        "axDisabled",
        "axDown",
        "axFault",
        "axNull",
        "axOverridden",
        "axStale",
        "axUnackedAlarm",

        "actions",    
        "cur",          
        "curStatus",
        "curVal",     
        "dis",            
        "enum",         
        "equip",
        "his",        
        "hisInterpolate", 
        "id",           
        "kind",
        "navName",    
        "point",          
        "site",         
        "tz",         
        "unit",       
        "writable"  
    };

    private static final Log LOG = Log.getLog("nhaystack");

    // point kinds
    private static final int UNKNOWN_KIND = -1;
    private static final int NUMERIC_KIND =  0;
    private static final int BOOLEAN_KIND =  1;
    private static final int ENUM_KIND    =  2;
    private static final int STRING_KIND  =  3;

    private final NHServer server;
    private final BNHaystackService service;
    private final SpaceManager spaceMgr;
    private final Cache cache;
}

