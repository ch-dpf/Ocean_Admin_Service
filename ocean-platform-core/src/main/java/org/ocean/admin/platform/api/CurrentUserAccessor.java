package org.ocean.admin.platform.api;

import org.ocean.admin.kernel.security.CurrentUser;

/** Public extension point for future ocean-business-* modules. */
public interface CurrentUserAccessor {
    CurrentUser requiredCurrentUser();
}

