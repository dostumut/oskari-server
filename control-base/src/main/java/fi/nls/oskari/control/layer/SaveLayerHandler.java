package fi.nls.oskari.control.layer;

import java.util.Date;

import javax.servlet.http.HttpServletRequest;

import fi.nls.oskari.annotation.OskariActionRoute;
import fi.nls.oskari.control.ActionDeniedException;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.util.JSONHelper;
import org.json.JSONObject;

import fi.mml.map.mapwindow.service.db.LayerClassService;
import fi.mml.map.mapwindow.service.db.LayerClassServiceIbatisImpl;
import fi.mml.map.mapwindow.service.db.MapLayerService;
import fi.mml.map.mapwindow.service.db.MapLayerServiceIbatisImpl;
import fi.mml.portti.domain.permissions.Permissions;
import fi.mml.portti.service.db.permissions.PermissionsService;
import fi.mml.portti.service.db.permissions.PermissionsServiceIbatisImpl;
import fi.nls.oskari.domain.map.CapabilitiesCache;
import fi.nls.oskari.domain.map.Layer;
import fi.nls.oskari.domain.map.wms.MapLayer;
import fi.nls.oskari.log.Logger;

import fi.nls.oskari.control.ActionException;
import fi.nls.oskari.control.ActionHandler;
import fi.nls.oskari.control.ActionParameters;

import fi.nls.oskari.util.ConversionHelper;
import fi.nls.oskari.util.GetWMSCapabilities;
import fi.nls.oskari.util.IOHelper;
import fi.nls.oskari.util.ResponseHelper;

/**
 * Admin insert/update of WMS map layer
 * 
 */
@OskariActionRoute("SaveLayer")
public class SaveLayerHandler extends ActionHandler {

    private MapLayerService mapLayerService = new MapLayerServiceIbatisImpl();
    private PermissionsService permissionsService = new PermissionsServiceIbatisImpl();
    private LayerClassService layerClassService = new LayerClassServiceIbatisImpl();
    
    private static final Logger log = LogFactory.getLogger(SaveLayerHandler.class);
    private static final String PARM_LAYER_ID = "layer_id";
    
    private static final String ADMIN_ID = "10113";

    @Override
    public void handleAction(ActionParameters params) throws ActionException {

        HttpServletRequest request = params.getRequest();

        try {

            final String layer_id = params.getHttpParam(PARM_LAYER_ID, "");
            final int mapLayerId = ConversionHelper.getInt(layer_id, 0);
            
            if(!mapLayerService.hasPermissionToUpdate(params.getUser(), mapLayerId)) {
                throw new ActionDeniedException("Unauthorized user tried to update layer - id=" + layer_id);
            }

            // ************** UPDATE ************************
            if (!layer_id.isEmpty()) {
                if (mapLayerId > 0) {
                    MapLayer ml = new MapLayer();
                    ml.setId(mapLayerId);
                    handleRequestToMapLayer(request, ml);

                    ml.setUpdated(new Date(System.currentTimeMillis()));
                    mapLayerService.update(ml);
                    
                    org.json.JSONObject mapJson = ml.toJSON();
                    mapJson.put("orgName",layerClassService.find(ml.getLayerClassId()).getNameFi());
                    
                    // update cache
                    updateCache(ml);
                    
                    ResponseHelper.writeResponse(params, mapJson.toString());
                }
            }

            // ************** INSERT ************************
            else {

                MapLayer ml = new MapLayer();
                ml.setCreated(new Date(System.currentTimeMillis()));
                ml.setUpdated(new Date(System.currentTimeMillis()));
                handleRequestToMapLayer(request, ml);
                int id = mapLayerService.insert(ml);
                ml.setId(id);
                addPermissionsForAdmin(ml);
                
                org.json.JSONObject mapJson = ml.toJSON();
                mapJson.put("orgName",layerClassService.find(ml.getLayerClassId()).getNameFi());

                // update cache
                insertCache(ml);

                ResponseHelper.writeResponse(params, mapJson.toString());
            }

        } catch (Exception e) {
            throw new ActionException("Couldn't update/insert map layer ", e);
        }
    }

    private int insertCache(MapLayer ml) throws ActionException {
        // retrieve capabilities
        CapabilitiesCache cc = new CapabilitiesCache();


        final String capabilitiesXML = GetWMSCapabilities.getResponse(ml.getWmsUrl());
        cc.setLayerId(ml.getId());
        cc.setData(capabilitiesXML);
        cc.setVersion(ml.getVersion());

        // update cache by inserting to db
        return mapLayerService.insertCapabilities(cc);
    }

    private void updateCache(MapLayer ml) throws ActionException {
        // retrieve capabilities
        CapabilitiesCache cc = mapLayerService.getCapabilitiesCache(ml.getId());
        final String capabilitiesXML = GetWMSCapabilities.getResponse(ml.getWmsUrl());
        cc.setData(capabilitiesXML);
        
        // update cache by updating db
        mapLayerService.updateCapabilities(cc);
    }
    
    private void handleRequestToMapLayer(HttpServletRequest request, MapLayer ml) {

        // FIXME: parameters are not filtered through getHttpParam, any reason for this?
        ml.setLayerClassId(new Integer(request.getParameter("lcId")));
        ml.setNameFi(request.getParameter("nameFi"));
        ml.setNameSv(request.getParameter("nameSv"));
        ml.setNameEn(request.getParameter("nameEn"));

        ml.setTitleFi(request.getParameter("titleFi"));
        ml.setTitleSv(request.getParameter("titleSv"));
        ml.setTitleEn(request.getParameter("titleEn"));

        setLocales(ml);

        ml.setWmsName(request.getParameter("wmsName"));
        ml.setWmsUrl(request.getParameter("wmsUrl"));

        String opacity = "0";
        if (request.getParameter("opacity") != null
                && !"".equals(request.getParameter("opacity"))) {
            opacity = request.getParameter("opacity");
        }

        ml.setOpacity(new Integer(opacity));
        String style = "";
        if (request.getParameter("style") != null
                && !"".equals(request.getParameter("style"))) {
            style = request.getParameter("style");
            style = IOHelper.decode64(style);
        }
        ml.setStyle(style);
        ml.setMinScale(new Double(request.getParameter("minScale")));
        ml.setMaxScale(new Double(request.getParameter("maxScale")));

        ml.setDescriptionLink(request.getParameter("descriptionLink"));
        ml.setLegendImage(request.getParameter("legendImage"));

        String inspireThemeId = request.getParameter("inspireTheme");
        Integer inspireThemeInteger = Integer.valueOf(inspireThemeId);
        ml.setInspireThemeId(inspireThemeInteger);

        ml.setDataUrl(request.getParameter("dataUrl"));
        ml.setMetadataUrl(request.getParameter("metadataUrl"));
        ml.setOrdernumber(new Integer(request.getParameter("orderNumber")));

        ml.setType(request.getParameter("layerType"));
        ml.setTileMatrixSetId(request.getParameter("tileMatrixSetId"));

        ml.setTileMatrixSetData(request.getParameter("tileMatrixSetData"));

        ml.setWms_dcp_http(request.getParameter("wms_dcp_http"));
        ml.setWms_parameter_layers(request
                        .getParameter("wms_parameter_layers"));
        ml.setResource_url_scheme(request.getParameter("resource_url_scheme"));
        ml.setResource_url_scheme_pattern(request
                .getParameter("resource_url_scheme_pattern"));
        ml.setResource_url_scheme_pattern(request
                .getParameter("resource_url_client_pattern"));

        if (request.getParameter("resource_daily_max_per_ip") != null) {
            ml.setResource_daily_max_per_ip(ConversionHelper.getInt(request
                    .getParameter("resource_daily_max_per_ip"), 0));
        }
        String xslt = "";
        if (request.getParameter("xslt") != null
                && !"".equals(request.getParameter("xslt"))) {
            xslt = request.getParameter("xslt");
            xslt = IOHelper.decode64(xslt);
        }
        ml.setXslt(xslt);
        ml.setGfiType(request.getParameter("gfiType"));
        String sel_style = "";
        if (request.getParameter("selection_style") != null
                && !"".equals(request.getParameter("selection_style"))) {
            sel_style = request.getParameter("selection_style");
            sel_style = IOHelper.decode64(sel_style);
        }
        ml.setSelection_style(sel_style);
        ml.setVersion(request.getParameter("version"));
        if (request.getParameter("epsg") != null) {
            ml.setEpsg(ConversionHelper.getInt(request.getParameter("epsg"),3067));
        }

    }
    
    private void addPermissionsForAdmin(MapLayer ml) {
        
        Permissions permissions = new Permissions();
        
        permissions.getUniqueResourceName().setType(Permissions.RESOUCE_TYPE_WMS_LAYER);
        permissions.getUniqueResourceName().setNamespace(ml.getWmsUrl());
        permissions.getUniqueResourceName().setName(ml.getWmsName());
        
        permissionsService.insertPermissions(permissions.getUniqueResourceName(), ADMIN_ID, Permissions.EXTERNAL_TYPE_ROLE, Permissions.PERMISSION_TYPE_VIEW_LAYER);
    }

    private void setLocales(MapLayer ml) {
        JSONObject locales = new JSONObject();
        JSONObject fi = new JSONObject();
        JSONObject sv = new JSONObject();
        JSONObject en = new JSONObject();

        JSONHelper.putValue(fi, "name", ml.getNameFi());
        JSONHelper.putValue(fi, "subtitle", ml.getTitleFi());

        JSONHelper.putValue(sv, "name", ml.getNameSv());
        JSONHelper.putValue(sv, "subtitle", ml.getTitleSv());

        JSONHelper.putValue(en, "name", ml.getNameEn());
        JSONHelper.putValue(en, "subtitle", ml.getTitleEn());

        JSONHelper.putValue(locales, "fi", fi);
        JSONHelper.putValue(locales, "sv", sv);
        JSONHelper.putValue(locales, "en", en);

        ml.setLocale(locales.toString());
    }
}