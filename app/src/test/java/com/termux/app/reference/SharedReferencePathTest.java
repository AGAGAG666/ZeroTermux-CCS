package com.termux.app.reference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public class SharedReferencePathTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();
    private static final String DOCS = "com.android.externalstorage";
    private static final String MI = "com.android.fileexplorer";
    private File find(File root, String pkg, String authority, String... parts) {
        return SharedReferencePath.find(root,true,pkg,authority,Arrays.asList(parts));
    }

    @Test public void primaryDocumentAndMiFileProviderResolveOriginal() throws Exception {
        File root = tmp.newFolder();
        File f = new File(root,"123.txt").getCanonicalFile(); assertTrue(f.createNewFile());
        assertEquals(f,find(root,DOCS,DOCS+".documents","document","primary:123.txt"));
        assertEquals(f,find(root,MI,MI+".myprovider","external_files","123.txt"));
        File nested = new File(root,"照片"); assertTrue(nested.mkdir());
        f = new File(nested,"100%2F (1).png").getCanonicalFile(); assertTrue(f.createNewFile());
        assertEquals(f,find(root,DOCS,DOCS+".documents","tree","primary:照片","document","primary:照片/100%2F (1).png"));
        assertEquals(f,find(root,MI,MI+".lazyfileprovider","external_files","照片","100%2F (1).png"));
    }

    @Test public void rejectsIncorrectProviderVolumeVirtualRootAndMissingFiles() throws Exception {
        File root = tmp.newFolder(), f = new File(root,"123.txt"); assertTrue(f.createNewFile());
        assertNull(SharedReferencePath.find(root,false,MI,MI+".myprovider",Arrays.asList("external_files","123.txt")));
        assertNull(find(root,MI,DOCS+".documents","document","primary:123.txt"));
        assertNull(find(root,MI,MI+".myprovider","cache","123.txt"));
        assertNull(find(root,DOCS,DOCS+".documents","document","other:123.txt"));
        assertNull(find(root,DOCS,DOCS+".documents","document","primary:folder/123.txt"));
    }

    @Test public void rejectsEscapesAndDoesNotSearchForDuplicateNames() throws Exception {
        File root = tmp.newFolder("shared"), outside = tmp.newFolder("other");
        assertTrue(new File(outside,"123.txt").createNewFile());
        Files.createSymbolicLink(new File(root,"linked").toPath(),outside.toPath());
        assertNull(find(root,MI,MI+".myprovider","external_files","..","other","123.txt"));
        assertNull(find(root,DOCS,DOCS+".documents","document","primary:linked/123.txt"));
        assertNull(find(root,DOCS,DOCS+".documents","document","primary:/123.txt"));
    }
}
