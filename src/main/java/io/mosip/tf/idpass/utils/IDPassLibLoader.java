package io.mosip.tf.idpass.utils;

import java.io.File;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;

import org.idpass.lite.IDPassLoader;
import org.idpass.lite.IDPassReader;

public class IDPassLibLoader extends IDPassLoader {

	private static int osType = UNSPECIFIED;

	public static boolean loadLibrary() {
		System.out.println("Hello from India");
		String idpasslib = "";
		String osName = System.getProperty("os.name");
		if (osName.startsWith("Debian GNU/Linux")) {
			if ("dalvik".equals(System.getProperty("java.vm.name").toLowerCase())) {
				osType = ANDROID; // TODO
				// Native libraries on android must be bundled with the APK
				System.setProperty("jna.nounpack", "true");
				System.loadLibrary("idpasslite");
				return true;
			} else {
				osType = LINUX;
				idpasslib = "linux64/libidpasslite.so";
			}
		} else if (osName.startsWith("Mac") || osName.startsWith("Darwin")) {
			osType = MAC; // TODO
		} else if (osName.startsWith("Windows")) {
			osType = WINDOWS;
			idpasslib = "windows64/idpasslite.dll";
		}

		String idpasslibFullPath = "";
		URL url = IDPassLoader.getThisJarPath(IDPassReader.class);
		System.out.println("url : " + url);

		try {
			boolean istemp1 = false;
			boolean istemp2 = false;

			File file = null;

			if (url.toString().startsWith("jar")) {
				String[] jarinjar = url.toString().split("!");
				url = doubleExtract(jarinjar);
				System.out.println("doubleExtract url : " + url);
				istemp1 = true;
			}

			if (IDPassLoader.isJarFile(url)) {
				// 1) create temp dir
				// 2) Unzip jar into temp dir
				// 3) Get absolute path of library from temp dir
				file = copyToTempDirectory(idpasslib, url);
				idpasslibFullPath = file.getAbsolutePath();
				System.out.println("idpasslibFullPath : " + idpasslibFullPath);
				istemp2 = true;
			} else {
				// 1) Get absolute path of library from resource
				file = new File(IDPassReader.class.getClassLoader().getResource(idpasslib).getFile());
				idpasslibFullPath = file.getAbsolutePath();
			}

			// TODO: cryptographically verify the native library!?
			// A signature can be embedded into the shared library
			// For example: `strings /path/to/libidpasslite.so | grep DXTRACKER`
			// returns the github commit hash where the library gets built
			System.load(idpasslibFullPath);

			String temppath;
			String s[];

			if (istemp1) {
				File file1 = Paths.get(url.toURI()).toFile();
				s = file1.toString().split(File.separator);
				temppath = File.separator + s[1] + File.separator + s[2];
				requestDeletion(new File(temppath));
			}

			if (istemp2) {
				s = file.toString().split(File.separator);
				temppath = File.separator + s[1] + File.separator + s[2];
				requestDeletion(new File(temppath));
			}

			return true;

		} catch (Exception e) {

		}

		return false;

	}
	
	private static boolean isPosixCompliant() {
        try {
            return FileSystems.getDefault()
                    .supportedFileAttributeViews()
                    .contains("posix");
        } catch (FileSystemNotFoundException |
                ProviderNotFoundException |
                SecurityException e) {
            return false;
        }
    }

    private static void rmrf(File dir) {
        File[] allContents = dir.listFiles();
        if (allContents != null) {
            for (File f : allContents) {
                rmrf(f);
            }
        }

        dir.delete();
    }

	private static void requestDeletion(File dir) {
		String tempdir = System.getProperty("java.io.tmpdir");
		if (dir.toString().startsWith(tempdir)) {
			if (isPosixCompliant()) {
				rmrf(dir);
			} else {
				dir.deleteOnExit();
			}
		}
	}
}
