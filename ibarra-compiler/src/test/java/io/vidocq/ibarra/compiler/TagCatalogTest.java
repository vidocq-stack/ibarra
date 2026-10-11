/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */

package io.vidocq.ibarra.compiler;

import io.vidocq.ibarra.compiler.internal.TldListeners;
import java.lang.module.ModuleDescriptor;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TagCatalogTest {
    @TempDir Path directory;
    private byte[] tld(String uri,String handler) {
        return ("<taglib><uri>"+uri+"</uri><tag><name>tag</name><tag-class>"+handler+"</tag-class><body-content>empty</body-content></tag></taglib>").getBytes(StandardCharsets.UTF_8);
    }
    @Test void libraryImplicitUrisPrecedeLooseTldsButExplicitAliasesRemainAuthoritative() throws Exception {
        Path library=directory.resolve("library");Files.createDirectories(library.resolve("META-INF"));
        Path info=directory.resolve("module-info.java");Files.writeString(info,"module tag.library { }");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","25","-d",library.toString(),info.toString()));
        Files.write(library.resolve("META-INF/library.tld"),tld("urn:shared","library.Handler"));
        Path output=directory.resolve("application");Files.createDirectories(output);
        var app=ModuleDescriptor.newModule("application.tags").requires("tag.library").build();
        var web=new TreeMap<String,byte[]>();
        web.put("/WEB-INF/local.tld",tld("urn:shared","application.Handler"));
        web.put("/external.tld",tld("urn:private","external.Handler"));
        web.put("/WEB-INF/web.xml","<web-app><jsp-config><taglib><taglib-uri>urn:alias</taglib-uri><taglib-location>/external.tld</taglib-location></taglib></jsp-config></web-app>".getBytes(StandardCharsets.UTF_8));
        var catalog=TldListeners.catalog(web,List.of(library),output,app);
        assertEquals("library.Handler",catalog.libraries().get("urn:shared").tags().get("tag").handlerClass());
        assertEquals("external.Handler",catalog.libraries().get("urn:alias").tags().get("tag").handlerClass());
        assertFalse(catalog.libraries().containsKey("urn:private"),"Explicit-only resources do not create implicit URI entries");
        web.put("/WEB-INF/web.xml","<web-app><jsp-config><taglib><taglib-uri>urn:shared</taglib-uri><taglib-location>/WEB-INF/local.tld</taglib-location></taglib></jsp-config></web-app>".getBytes(StandardCharsets.UTF_8));
        assertEquals("application.Handler",TldListeners.catalog(web,List.of(library),output,app).libraries().get("urn:shared").tags().get("tag").handlerClass());
    }
}
