package fi.nls.oskari.control.statistics.plugins;

import java.util.List;

import fi.nls.oskari.annotation.Oskari;
import fi.nls.oskari.control.statistics.plugins.db.StatisticalDatasource;
import fi.nls.oskari.domain.User;
import fi.nls.oskari.service.OskariComponent;
import org.json.JSONObject;

public class MockPlugin implements StatisticalDatasourcePlugin {

    @Override
    public List<StatisticalIndicator> getIndicators(User user) {
        return null;
    }

    @Override
    public void init(StatisticalDatasource source) {
    }

    @Override
    public boolean canCache() {
        return false;
    }

}
