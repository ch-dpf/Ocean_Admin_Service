package org.ocean.admin.platform.api;

import java.util.List;
import java.util.Map;

/** Optional workbench contribution implemented by future business modules. */
public interface WorkbenchContributor {
    String platformCode();

    List<Map<String, Object>> cards();
}

