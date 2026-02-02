package org.btsn.utils;

import java.awt.EventQueue;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Vector;

import nu.xom.Elements;

import org.apache.log4j.Logger;
import org.ruleml.oojdrew.Config;
import org.ruleml.oojdrew.Configuration;
import org.ruleml.oojdrew.TopDown.BackwardReasoner;
import org.ruleml.oojdrew.parsing.RuleMLParser;
import org.ruleml.oojdrew.parsing.SubsumesParser;
import org.ruleml.oojdrew.parsing.TypeQueryParserRuleML;
import org.ruleml.oojdrew.util.DefiniteClause;
import org.ruleml.oojdrew.util.LUBGLBStructure;
import org.ruleml.oojdrew.util.QueryTypes;
import org.ruleml.oojdrew.util.SubsumesStructure;
import org.ruleml.oojdrew.util.SymbolTable;

public class OOjdrewAPI_orginal {
	// private Configuration config;
	// private TopDownUI ui;
	// private DebugConsole debugConsole;
	private Logger logger;

	// TODO: Rewrite all code that uses the following variables
	// These variables were copied from the old UI
	private Iterator<?> solit;
	private Iterator<String> it;
	private boolean t1Var;
	private boolean t2Var;
	private String term1VarName;
	private String term2VarName;
	private SubsumesStructure subPlus;
	private SubsumesStructure sub;
	private LUBGLBStructure lub;
	private LUBGLBStructure glb;
	// private String ruleBase;
	// private static int MAXROWS = 16;
	// private static int MAXCOLS = 2;
	private static final int MAXROWS = 16;
	private static final int MAXCOLS = 2;

	Configuration config = new Config(OOjdrewAPI_orginal.class);

	// Create the parsers
	// RDFSParser rdfsParser = new RDFSParser();
	// POSLParser poslParser = new POSLParser();
	RuleMLParser rmlParser = new RuleMLParser(config);
	SubsumesParser subsumesParser = new SubsumesParser();

	// Create the reasoning engine
	BackwardReasoner backwardReasoner = new BackwardReasoner();
	public Object rowData[][] = new Object[MAXROWS][MAXCOLS];
	public boolean hasNext = false;
	public int rowsReturned = 0;

	// public void parseResult(boolean x, Object[][] result, int rowsReturned) {
	// TODO Auto-generated method stub

	// }
	public void run() {
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				// logger.debug("Entering event loop");
				// ui.setFrameVisible(true);
			}
		});
	}

	public String getRuleBaset(String ruleFileName) {
		String ruleBase;
		try {

			// right before you try to read from the file
			File appBase = new File(""); // current directory
			// String path = appBase.getAbsolutePath() + ruleFileName;
			String path = appBase.getCanonicalPath() + ruleFileName;
			// System.out.println(path);

			FileReader fileReader = new FileReader(path);

			BufferedReader bufferedReader = new BufferedReader(fileReader);

			StringBuilder stringBuilder = new StringBuilder();
			String lineSeparator = System.getProperty("line.separator");
			String currentLine;

			while ((currentLine = bufferedReader.readLine()) != null) {
				stringBuilder.append(currentLine);
				stringBuilder.append(lineSeparator);
			}

			ruleBase = stringBuilder.toString();
			bufferedReader.close();
		} catch (IOException e) {
			defaultExceptionHandler(e);
			return null;
		}
		return ruleBase;
	}

	public void parseKnowledgeBase(String RuleBase, boolean loadFromFile) {
		// BackwardReasoner backwardReasoner = new BackwardReasoner();
		String knowledgeBase = null;
		SymbolTable.reset();
		if (loadFromFile)
			knowledgeBase = getRuleBaset(RuleBase);
		else
			knowledgeBase = RuleBase;
		backwardReasoner.clearClauses();

		if (knowledgeBase.isEmpty()) {
			return;
		}
		parseRuleMLKnowledeBase(knowledgeBase);

	}

	private void parseRuleMLKnowledeBase(String knowledgeBase) {
		rmlParser.clear();

		try {
			rmlParser.parseRuleMLString(knowledgeBase);
		} catch (Exception e) {
			defaultExceptionHandler(e);
			return;
		}

		backwardReasoner.loadClauses(rmlParser.iterator());
	}

	private void defaultExceptionHandler(Exception e) {
		// JOptionPane.showMessageDialog(ui.getFrmOoJdrew(), e.getMessage(),
		// "Error", JOptionPane.ERROR_MESSAGE);
		logger.error(e.getMessage());
	}

	public void issueRuleMLQuery(String query) {
		// rmlParser.notify();
		rmlParser.clear();
		// System.err.println("in oojdrew printing query....\n\r" + query);

		try {
			// System.err.println("entering DefiniteClause...");
			DefiniteClause dc = rmlParser.parseRuleMLQuery(query);
			// System.err.println("entering processQuery...");
			processQuery(dc);
		} catch (Exception e) {
			defaultExceptionHandler(e);
		}
	}

	// TODO: This method was copied from the old GUI and has been modified to
	// work with the current code base. This code should be rewritten in a much
	// cleaner fashion.
	private void processQuery(DefiniteClause dc) {
		// TODO: Find a way to use the existing backwardReasoner (for the sake
		// of dependency injection)
		// Reinitialise the Array
		Object[][] rowdata = new Object[MAXROWS][MAXCOLS];
		System.arraycopy(rowdata, 0, rowData, 0, MAXROWS);
		rowsReturned = 0;

		backwardReasoner = new BackwardReasoner(backwardReasoner.clauses, backwardReasoner.oids);

		solit = backwardReasoner.iterativeDepthFirstSolutionIterator(dc);

		if (!solit.hasNext()) {

		} else {
			BackwardReasoner.GoalList gl = (BackwardReasoner.GoalList) solit.next();

			Hashtable varbind = gl.varBindings;

			int i = 0;
			// Object[][] rowdata = new Object[varbind.size()][2];
			// System.arraycopy( rowdata, 0, rowData, 0, i );

			Enumeration e = varbind.keys();

			while (e.hasMoreElements()) {
				Object k = e.nextElement();
				Object val = varbind.get(k);
				String ks = (String) k;
				rowData[i][0] = ks;
				rowData[i][1] = val;
				i++;
			}
			hasNext = solit.hasNext();
			rowsReturned = i;
		}

	}

	// TODO: This method was copied from the old GUI and has been modified to
	// work with the current code base. This code should be rewritten in a much
	// cleaner fashion.
	public void nextSolution() {
		BackwardReasoner.GoalList gl = (BackwardReasoner.GoalList) solit.next();
		// System.out.println(gl.toString());
		Hashtable varbind = gl.varBindings;

		int i = 0;
		// Object[][] rowdata = new Object[varbind.size()][2];
		Enumeration e = varbind.keys();
		while (e.hasMoreElements()) {
			Object k = e.nextElement();
			Object val = varbind.get(k);
			String ks = (String) k;
			rowData[i][0] = ks;
			rowData[i][1] = val;
			i++;
		}
		// rowData = rowdata;
		hasNext = solit.hasNext();
		rowsReturned = i;

	}

	// TODO: This method was copied from the old GUI and has been modified to
	// work with the current code base. This code should be rewritten in a much
	// cleaner fashion.
	private void issueRuleMLTypeQuery(String query) {
		Object[][] resetRow = new Object[2][2];
		String[] resetCol = new String[] { "Variable", "Binding" };

		// ui.setVariableBindingsTableModel(new
		// javax.swing.table.DefaultTableModel(resetRow, resetCol));

		// ui.setBtnNextSolutionEnabled(false);

		// It is an iterator that is used to map all the solutions to bindings
		it = null;
		// Creating a QueryTypes objects
		QueryTypes typeQuery = new QueryTypes();

		if (query.equals("")) {
			return;
		}

		try {

			// need to get rid of this eventually
			t1Var = false;
			t2Var = false;
			term1VarName = "";
			term2VarName = "";

			// ui.setSolutionTextAreaText("");
			TypeQueryParserRuleML rmlTParser = new TypeQueryParserRuleML(query);
			Elements elements = rmlTParser.parseForPredicate();

			String predicate = rmlTParser.getPredicate();

			if (predicate.equalsIgnoreCase(TypeQueryParserRuleML.SUBSUMESPLUS)) {

				subPlus = rmlTParser.parseElementsSubsumesAndSubsumesPlus(elements);

				// rel rel
				if (!subPlus.getSuperVar() && !subPlus.getSubVar()) {
					// ui.setSolutionTextAreaText(""
					// + typeQuery.isSuperClass(subPlus.getSuperName(),
					// subPlus.getSubName()));
					// var rel get all super classes
				} else if (subPlus.getSuperVar() && !subPlus.getSubVar()) {
					t1Var = true;
					term1VarName = subPlus.getSuperName();

					String[] superClasses = typeQuery.findAllSuperClasses(subPlus.getSubName());

					Object[][] rowdata = new Object[2][2];
					rowdata[0][0] = "?" + subPlus.getSuperName();
					rowdata[0][1] = superClasses[0];
					String[] colnames = new String[] { "Variable", "Binding" };
					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));

					Vector<String> nextVector = new Vector<String>();
					for (int i = 1; i < superClasses.length; i++)
						nextVector.add(superClasses[i]);

					it = nextVector.iterator();

					// if (it.hasNext()) {
					// ui.setBtnNextSolutionEnabled(true);
					// }

					// rel var get all sub classes
				} else if (!subPlus.getSuperVar() && subPlus.getSubVar()) {
					t2Var = true;
					term2VarName = subPlus.getSubName();
					String[] subClasses = typeQuery.findAllSubClasses(subPlus.getSuperName());

					for (int i = 0; i < subClasses.length; i++)
						System.out.println(subClasses[i]);

					Object[][] rowdata = new Object[2][2];

					rowdata[0][0] = "?" + subPlus.getSubName();
					rowdata[0][1] = subClasses[0];

					String[] colnames = new String[] { "Variable", "Binding" };

					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));

					Vector nextVector = new Vector();
					for (int i = 1; i < subClasses.length; i++)
						nextVector.add(subClasses[i]);

					it = nextVector.iterator();

					// if (it.hasNext()) {
					// ui.setBtnNextSolutionEnabled(true);
					// }

					// var var get all relations
				} else if (subPlus.getSuperVar() && subPlus.getSubVar()) {
					t1Var = true;
					t2Var = true;
					term2VarName = subPlus.getSubName();
					term1VarName = subPlus.getSuperName();

					if (subPlus.getSuperName().equalsIgnoreCase(subPlus.getSubName())) {
						// JOptionPane.showMessageDialog(ui.getFrmOoJdrew(),
						// "Duplicate variable names not allowed",
						// "Error", JOptionPane.ERROR_MESSAGE);
						return;
					}
					Vector v1 = typeQuery.findAllSuperClassesOfEverything();
					Vector v2 = typeQuery.findAllSubClassesOfEverything();
					String sol = "";
					Iterator vit1 = v1.iterator();
					Iterator vit2 = v2.iterator();
					int count = 0;
					// Debug -> Prints out all the solutions for easy Copy and
					// Paste
					sol = "% Taxonomy Facts: \n";
					while (vit1.hasNext()) {
						count++;
						sol = sol + "subsumes(" + vit1.next().toString() + "," + vit1.next().toString() + ")." + "\n";
					}
					// ui.setSolutionTextAreaText(sol);
					// Debug

					it = v1.iterator();

					Object[][] rowdata = new Object[2][2];

					rowdata[0][0] = "?" + subPlus.getSuperName();
					rowdata[0][1] = (String) it.next();

					rowdata[1][0] = "?" + subPlus.getSubName();
					rowdata[1][1] = (String) it.next();

					String[] colnames = new String[] { "Variable", "Binding" };

					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));

					// if (it.hasNext()) {
					// ui.setBtnNextSolutionEnabled(true);
					// }

				}
			} else if (predicate.equalsIgnoreCase(TypeQueryParserRuleML.SUBSUMES)) {
				sub = rmlTParser.parseElementsSubsumesAndSubsumesPlus(elements);
				// rel rel
				if (!sub.getSuperVar() && !sub.getSubVar()) {
					// ui.setSolutionTextAreaText("" +
					// typeQuery.isDirectSuperClass(sub.getSuperName(),
					// sub.getSubName()));
					// var rel
				} else if (sub.getSuperVar() && !sub.getSubVar()) {
					t1Var = true;
					term1VarName = sub.getSuperName();

					String[] superClasses = typeQuery.getDirectSuperClasses(sub.getSubName());

					for (int i = 0; i < superClasses.length; i++)
						System.out.println(superClasses[i]);

					Object[][] rowdata = new Object[2][2];

					rowdata[0][0] = "?" + sub.getSuperName();
					rowdata[0][1] = superClasses[0];

					String[] colnames = new String[] { "Variable", "Binding" };

					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));
					Vector nextVector = new Vector();
					for (int i = 1; i < superClasses.length; i++)
						nextVector.add(superClasses[i]);

					it = nextVector.iterator();

					// if (it.hasNext()) {
					// ui.setBtnNextSolutionEnabled(true);
					// }

					// rel var
				} else if (!sub.getSuperVar() && sub.getSubVar()) {
					t2Var = true;
					term2VarName = sub.getSubName();

					String[] subClasses = typeQuery.getDirectSubClasses(sub.getSuperName());

					for (int i = 0; i < subClasses.length; i++)
						System.out.println(subClasses[i]);

					Object[][] rowdata = new Object[2][2];

					rowdata[0][0] = "?" + sub.getSubName();
					rowdata[0][1] = subClasses[0];

					String[] colnames = new String[] { "Variable", "Binding" };

					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));
					Vector nextVector = new Vector();
					for (int i = 1; i < subClasses.length; i++)
						nextVector.add(subClasses[i]);

					it = nextVector.iterator();
					// if (it.hasNext()) {
					// ui.setBtnNextSolutionEnabled(true);
					// }
					// var var
				} else if (sub.getSuperVar() && sub.getSubVar()) {
					t1Var = true;
					t2Var = true;
					term2VarName = sub.getSubName();
					term1VarName = sub.getSuperName();

					if (sub.getSuperName().equalsIgnoreCase(sub.getSubName())) {
						// JOptionPane.showMessageDialog(ui.getFrmOoJdrew(),
						// "Duplicate variable names not allowed",
						// "Error", JOptionPane.ERROR_MESSAGE);
						return;
					}
					Vector v1 = typeQuery.findAllDirectSuperClassesOfEverything();
					Vector v2 = typeQuery.findAllDirectSubClassesOfEverything();
					String sol = "";
					Iterator vit1 = v1.iterator();
					Iterator vit2 = v2.iterator();
					int count = 0;
					// Debug -> Prints out all the solutions for easy Copy and
					// Paste
					sol = "% Taxonomy Facts: \n";
					while (vit1.hasNext()) {
						count++;
						sol = sol + "subsumes(" + vit1.next().toString() + "," + vit1.next().toString() + ")." + "\n";
					}
					// ui.setSolutionTextAreaText(sol);
					// Debug

					it = v1.iterator();

					Object[][] rowdata = new Object[2][2];

					rowdata[0][0] = "?" + sub.getSuperName();
					rowdata[0][1] = (String) it.next();

					rowdata[1][0] = "?" + sub.getSubName();
					rowdata[1][1] = (String) it.next();

					String[] colnames = new String[] { "Variable", "Binding" };

					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));

					// if (it.hasNext()) {
					// ui.setBtnNextSolutionEnabled(true);
					// }

				}

			} else if (predicate.equalsIgnoreCase(TypeQueryParserRuleML.LUB)) {

				lub = rmlTParser.parseElementsGLBandLUB(elements);

				if (lub.getResultVar()) {

					ArrayList<String> terms = lub.getTerms();

					String[] lubArray = new String[terms.size()];

					for (int i = 0; i < terms.size(); i++)
						lubArray[i] = terms.get(i);

					String leastUpperBound = typeQuery.leastUpperBound(lubArray);

					Object[][] rowdata = new Object[2][2];

					rowdata[0][0] = "?" + lub.getResultVarName();
					rowdata[0][1] = leastUpperBound;

					String[] colnames = new String[] { "Variable", "Binding" };

					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));

				} else if (!lub.getResultVar()) {

					Object[][] rowdata = new Object[2][2];
					String[] colnames = new String[] { "Variable", "Binding" };
					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));
					// ui.setSolutionTextAreaText("");

					ArrayList<String> terms = lub.getTerms();

					String[] lubArray = new String[terms.size()];

					for (int i = 0; i < terms.size(); i++)
						lubArray[i] = terms.get(i);

					String leastUpperBound = typeQuery.leastUpperBound(lubArray);
					// ui.setSolutionTextAreaText(leastUpperBound);
				}
			} else if (predicate.equalsIgnoreCase(TypeQueryParserRuleML.GLB)) {

				glb = rmlTParser.parseElementsGLBandLUB(elements);

				if (glb.getResultVar()) {

					ArrayList<String> terms = glb.getTerms();

					String[] glbArray = new String[terms.size()];

					for (int i = 0; i < terms.size(); i++)
						glbArray[i] = terms.get(i);

					String greatestLowerBound = typeQuery.greatestLowerBound(glbArray);

					Object[][] rowdata = new Object[2][2];

					rowdata[0][0] = "?" + glb.getResultVarName();
					rowdata[0][1] = greatestLowerBound;

					String[] colnames = new String[] { "Variable", "Binding" };

					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));

				} else if (!glb.getResultVar()) {

					Object[][] rowdata = new Object[2][2];
					String[] colnames = new String[] { "Variable", "Binding" };
					// ui.setVariableBindingsTableModel(new
					// javax.swing.table.DefaultTableModel(rowdata, colnames));
					// ui.setSolutionTextAreaText("");

					ArrayList<String> terms = glb.getTerms();

					String[] glbArray = new String[terms.size()];

					for (int i = 0; i < terms.size(); i++) {
						glbArray[i] = terms.get(i);
					}

					String greatestLowerBound = typeQuery.greatestLowerBound(glbArray);
					// ui.setSolutionTextAreaText(greatestLowerBound);
				}

			}

		} catch (Exception ex) {
			// JOptionPane.showMessageDialog(ui.getFrmOoJdrew(),
			// ex.getMessage(), "Type Query Parser Exeception",
			// JOptionPane.ERROR_MESSAGE);
			System.err.println("oojdrew exception: " + ex);
		}
	}

}
