package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import java.util.Random;

import org.junit.Test;

public class IntRollerTest {

    static Random RANDOM = new Random();

    @Test
    public void roll()
    {
        int length = 50;
        final int maxForm = 6;
        final int[][] matrix = new int[maxForm][maxForm];
        final int left = 2;
        final int right = 3;
        IntRoller roller = new IntRoller(1 + left + right);
        roller.fill(-1);
        while (length-- > 0) {
            final int value = RANDOM.nextInt(maxForm);
            roller.add(value);
            System.out.println(length + " " + print(roller, left, right));
            final int pivot = roller.get(-right);
            if (pivot < 0) continue;
            for (int i = -(left + right); i <= 0; i++) {
                if (i == (- right)) continue;
                final int cooc = roller.get(i);
                if (cooc < 0) continue;
                matrix[pivot][cooc]++;
            }
            System.out.println(print(matrix));
        }
    }
    
    private String print(int[][] matrix)
    {
        StringBuilder sb = new StringBuilder();
        for (int x=0; x < matrix.length; x++) {
            final int[] row = matrix[x];
            for (int y = 0; y < row.length; y++) {
                if (y != 0) sb.append(" ");
                sb.append(matrix[x][y]);
            }
            sb.append("\n");
        }
        return sb.toString();
    }


    private String print(IntRoller roller, final int left, final int right)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("      ");
        boolean first = true;
        for (int i = -(left + right); i <= 0; i++) {
            if (first) first = false;
            else sb.append(" ");
            if (i == (- right)) sb.append("|");
            sb.append(roller.get(i));
            if (i == (- right )) sb.append("|");
        }
        return sb.toString();
    }

}

