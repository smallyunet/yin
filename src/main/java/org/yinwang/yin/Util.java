package org.yinwang.yin;

import org.yinwang.yin.ast.Node;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;

public class Util {

    public static String readFile(String path) {
        try {
            byte[] encoded = Files.readAllBytes(Paths.get(path));
            return Charset.forName("UTF-8").decode(ByteBuffer.wrap(encoded)).toString();
        } catch (IOException e) {
            return null;
        }
    }


    public static void msg(String m) {
        System.out.println(m);
    }


    public static void abort(String m) {
        throw new GeneralError(m);
    }


    public static void abort(Node loc, String msg) {
        throw new GeneralError(loc, msg);
    }


    public static String joinWithSep(Collection<?> ls, String sep) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Object s : ls) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(s.toString());
            i++;
        }
        return sb.toString();
    }


    public static String unifyPath(String filename) {
        return unifyPath(new File(filename));
    }


    public static String unifyPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            abort("Failed to get canonical path");
            return "";
        }
    }

}
