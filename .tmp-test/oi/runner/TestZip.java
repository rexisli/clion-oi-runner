package oi.runner;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class TestZip {
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("oitest");
        Path zip = tmp.resolve("cases.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), Charset.forName("GBK"))) {
            add(out, "1.in", "1 2\n");
            add(out, "1.out", "3\n");
            add(out, "2.in", "5 6\n");
            add(out, "2.ans", "11\n");
            add(out, "sub/10.in", "10\n");
            add(out, "sub/10.out", "100\n");
            add(out, "\u4e2d\u6587.in", "7\n");
            add(out, "\u4e2d\u6587.out", "8\n");
            add(out, "readme.txt", "ignore me");
        }
        Method m = Class.forName("oi.runner.OiCasePanel").getDeclaredMethod("casesFromZip", Path.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<OiCase> cases = (List<OiCase>) m.invoke(null, zip);
        System.out.println("count=" + cases.size());
        for (OiCase c : cases) {
            System.out.println("[" + c.name + "] in=" + c.input.trim() + " expected=" + c.expected.trim());
        }
        if (cases.size() != 4) throw new AssertionError("expected 4 cases");
        if (!cases.get(0).name.equals("1") || !cases.get(0).expected.equals("3\n")) throw new AssertionError("first case wrong");

        // a folder whose file names contain a GBK name and no .out for the last input
        Path dir = tmp.resolve("folder");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.in"), "a\n");
        Files.writeString(dir.resolve("1.out"), "b\n", Charset.forName("GBK"));
        Files.writeString(dir.resolve("\u4e2d\u6587.in"), "\u4f60\u597d\n", Charset.forName("GBK"));
        Files.writeString(dir.resolve("\u4e2d\u6587.out"), "\u4f60\u597d\n");
        Method f = Class.forName("oi.runner.OiCasePanel").getDeclaredMethod("casesFromFolder", Path.class);
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<OiCase> fromFolder = (List<OiCase>) f.invoke(null, dir);
        System.out.println("folder count=" + fromFolder.size());
        for (OiCase c : fromFolder) System.out.println("[" + c.name + "] in=" + c.input.trim() + " expected=" + c.expected.trim());
        if (!fromFolder.get(1).input.equals("\u4f60\u597d\n")) throw new AssertionError("GBK decode failed");
        System.out.println("OK");
        // cleanup
        try (var walk = Files.walk(tmp)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
    }

    private static void add(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}