package org.btsn.utils;

import java.util.Stack;

public class BalancedParenthensies {

	public static boolean isBalanced(String str) {
		Stack<Character> stack = new Stack<Character>();
		for (int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			if (c == '<' || c == '[' || c == '(' || c == '{') {
				stack.push(c);
			} else if (c == '>') {
				if (stack.isEmpty() || stack.pop() != '<') {
					return false;
				}
			} else if (c == ']') {
				if (stack.isEmpty() || stack.pop() != '[') {
					return false;
				}
			} else if (c == ')') {
				if (stack.isEmpty() || stack.pop() != '(') {
					return false;
				}
			} else if (c == '}') {
				if (stack.isEmpty() || stack.pop() != '{') {
					return false;
				}
			}

		}
		return stack.isEmpty();
	}
	/*
	 * public static void main(String args[]) {
	 * 
	 * System.out.println(isBalanced("{(artertererterb)}")); System.out.println(isBalanced("<....><.....><....>"));
	 * System.out.println(isBalanced("{)(a,b}")); }
	 */

}
