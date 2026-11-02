/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.webdashboard;

import de.linzn.simplyConfiguration.FileConfiguration;
import de.linzn.stem.modules.pluginModule.STEMPlugin;
import de.linzn.webapi.WebApiPlugin;
import de.linzn.webapi.core.WebServer;

import java.io.File;

/**
 * Hosts the MirraNET web dashboard on the web server of web-api, behind its login. The page and the API share one
 * login and one origin, so no reverse proxy login is needed any more.
 */
public class WebDashboardPlugin extends STEMPlugin {

    public static WebDashboardPlugin webDashboardPlugin;
    private String path;

    public WebDashboardPlugin() {
        webDashboardPlugin = this;
    }

    @Override
    public void onEnable() {
        FileConfiguration config = this.getDefaultConfig();
        /* path of the dashboard on the web server; "/" also answers every path no API module takes */
        this.path = config.getString("web.path", "/");
        /* the web page (e.g. the export of Bootstrap Studio), relative to plugins/web-dashboard/ or absolute */
        String web = config.getString("web.folder", "public");
        config.save();
        WebServer server = WebApiPlugin.webApiPlugin == null ? null : WebApiPlugin.webApiPlugin.getWebServer();
        if (server == null) {
            this.getPluginLogger().error("web-api is not running, the dashboard is not available");
            return;
        }
        File folder = new File(web);
        if (!folder.isAbsolute()) {
            folder = new File(this.getDataFolder(), web);
        }
        if (!folder.isDirectory() && !folder.mkdirs()) {
            this.getPluginLogger().error("Could not create the web folder " + folder.getAbsolutePath());
        }
        if (!new File(folder, "index.html").isFile()) {
            this.getPluginLogger().warning("No index.html in " + folder.getAbsolutePath() + " - copy the web page (export of Bootstrap Studio) there");
        }
        server.registerPage(this.path, new StaticFiles(this.path, folder.toPath(), server.getBasePath()));
        this.getPluginLogger().info("Dashboard at " + this.path + " on port " + server.getPort() + ", files from " + folder.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        if (WebApiPlugin.webApiPlugin != null && WebApiPlugin.webApiPlugin.getWebServer() != null && this.path != null) {
            WebApiPlugin.webApiPlugin.getWebServer().unregisterPage(this.path);
        }
    }
}
