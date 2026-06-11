package com.fpt.train.dbcli;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ResultSetPrinter {

    private ResultSetPrinter() {
    }

    public static void print(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String[]> rows = new ArrayList<>();
        int[] widths = new int[columnCount];

        for (int i = 1; i <= columnCount; i++) {
            widths[i - 1] = metaData.getColumnLabel(i).length();
        }

        while (resultSet.next()) {
            String[] row = new String[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                String value = String.valueOf(resultSet.getObject(i));
                row[i - 1] = value;
                widths[i - 1] = Math.max(widths[i - 1], value.length());
            }
            rows.add(row);
        }

        printHeader(metaData, widths);
        printSeparator(widths);

        if (rows.isEmpty()) {
            System.out.println("(0 rows)");
            return;
        }

        for (String[] row : rows) {
            printRow(row, widths);
        }

        System.out.printf("(%d row(s))%n", rows.size());
    }

    private static void printHeader(ResultSetMetaData metaData, int[] widths) throws SQLException {
        String[] header = new String[widths.length];
        for (int i = 1; i <= widths.length; i++) {
            header[i - 1] = metaData.getColumnLabel(i);
        }
        printRow(header, widths);
    }

    private static void printSeparator(int[] widths) {
        StringBuilder builder = new StringBuilder();
        for (int width : widths) {
            builder.append("+");
            builder.append("-".repeat(width + 2));
        }
        builder.append("+");
        System.out.println(builder);
    }

    private static void printRow(String[] values, int[] widths) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            builder.append("| ");
            builder.append(padRight(values[i], widths[i]));
            builder.append(' ');
        }
        builder.append("|");
        System.out.println(builder);
    }

    private static String padRight(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }
}
