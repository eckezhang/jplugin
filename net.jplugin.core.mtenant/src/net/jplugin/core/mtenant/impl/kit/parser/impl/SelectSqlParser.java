package net.jplugin.core.mtenant.impl.kit.parser.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;

import net.jplugin.core.mtenant.impl.kit.parser.SqlParser;
import net.jplugin.core.mtenant.impl.kit.utils.SqlHelper;
import net.jplugin.core.mtenant.impl.kit.utils.StringUtils;

public class SelectSqlParser implements SqlParser {

	@Override
	public String parse(String sourceSql, Map<String, Object> params,
			List<String> ignoreTables) {
		if (params.isEmpty()) {
			return sourceSql;
		}
		if (!sourceSql.contains("from")) {
			return sourceSql;
		}
		String returnSql = sourceSql;
		if (sourceSql.contains("union all")) {
			String preSql = SqlHelper.format(sourceSql.substring(0,sourceSql.indexOf("union all")));
			String subSql = SqlHelper.format(sourceSql.substring(sourceSql.indexOf("union all")).replace("union all", ""));
			returnSql = parse(preSql, params, ignoreTables) + " union all "
					+ parse(subSql, params, ignoreTables);

		} else {
			if (!sourceSql.contains("(")) {
				returnSql = preParse(sourceSql, params, ignoreTables);
			} else {
				returnSql = dealBracket(sourceSql, params, ignoreTables);
			}
		}

		boolean havePlatform = false;
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			if (returnSql.contains(entry.getKey())) {
				havePlatform = true;
				break;
			}
		}
		boolean haveIgnore = false;
		if (!havePlatform) {
			for (String ignore : ignoreTables) {
				if (returnSql.contains(ignore)) {
					haveIgnore = true;
					break;
				}
			}
		}
		// 包含from字符的sql语句，如果不是例外表，则必须包含platform_num
		if (!haveIgnore && !havePlatform && !returnSql.contains("from")) {
			throw new IllegalArgumentException("SQL parse error");
		} else {
			return returnSql;
		}

	}

	public String preParse(String sourceSql, Map<String, Object> params,
			List<String> ignoreTables) {
		if (params.isEmpty()) {
			return sourceSql;
		}
		if (!sourceSql.contains("select")) {
			return sourceSql;
		}

		String[] sqlArray = StringUtils.split(sourceSql, " ");
		if (ignoreTables == null) {
			ignoreTables = new ArrayList<>();
		}
		boolean whereIf = false;
		int length = sqlArray.length;
		TreeMap<Integer, String> map = new TreeMap<>();
		Set<String> aliasList = new HashSet<String>();
		int fromPosition = 0;
		int wherePosition = 0;
		int beforeByPosition = length;
		for (int i = 0; i < length; i++) {
			if ("from".equals(sqlArray[i])) {
				fromPosition = i;
				if (i + 1 == length) {
					// select * from
					throw new RuntimeException("error sql: " + sourceSql);
				}

			}
			if ("limit".equals(sqlArray[i])
					|| (("order".equals(sqlArray[i]) || "group"
							.equals(sqlArray[i])) && "by"
							.equals(sqlArray[i + 1]))) {
				// 只对beforByPosition取最小值
				if (i < beforeByPosition) {
					beforeByPosition = i;
				}
			}
			if ("where".equals(sqlArray[i])) {
				wherePosition = i;
				map.put(i + 1, "Y");
				whereIf = true;

			}
		}
		StringBuilder sb = new StringBuilder();
		if (whereIf) {
			for (int i = fromPosition + 1; i < wherePosition; i++) {

				if (!"as".equals(sqlArray[i]) && !"on".equals(sqlArray[i])
						&& !sqlArray[i].contains(".")
						&& !sqlArray[i].equals("=")
						&& !sqlArray[i].equals("join")
						&& !sqlArray[i].equals("and")
						&& !sqlArray[i].equals("or")) {
					if (("left".equals(sqlArray[i])
							|| "right".equals(sqlArray[i])
							|| "inner".equals(sqlArray[i]) || "full"
								.equals(sqlArray[i]))
							&& "join".equals(sqlArray[i + 1])) {
						sb.append(",");
					} else {

						sb.append(sqlArray[i]).append(" ");
					}
				}

			}
		}
		// 表示不存在where
		if (wherePosition == 0) {
			for (int i = fromPosition + 1; i < beforeByPosition; i++) {
				if (!"as".equals(sqlArray[i]) && !"on".equals(sqlArray[i])
						&& !sqlArray[i].contains(".")
						&& !sqlArray[i].equals("=")
						&& !sqlArray[i].equals("join")
						&& !sqlArray[i].equals("and")
						&& !sqlArray[i].equals("or")) {
					if (("left".equals(sqlArray[i])
							|| "right".equals(sqlArray[i])
							|| "inner".equals(sqlArray[i]) || "full"
								.equals(sqlArray[i]))
							&& "join".equals(sqlArray[i + 1])) {
						sb.append(",");
					} else {

						sb.append(sqlArray[i]).append(" ");
					}
				}
			}

		}
		String[] tables = StringUtils.split(sb.toString().trim(), ",");
		for (String t : tables) {
			if (StringUtils.contains(t.trim(), " ")) {
				aliasList.add(StringUtils.split(t.trim(), " ")[1]);

			} else {
				aliasList.add(t.trim());
			}
		}

		if (0 < aliasList.size()) {
			Map<String, Object> bridgeMap = new HashMap<String, Object>();
			for (Map.Entry<String, Object> entry : params.entrySet()) {

				for (String alias : aliasList) {
					if (!ignoreTables.contains(alias)
							&& !alias.startsWith("tempT_able_")) {
						bridgeMap.put(alias + "." + entry.getKey(),
								entry.getValue());
					}
				}
			}
			params = bridgeMap;
		}
		List<String> bracketList = new ArrayList<>();
		// 如没有where
		if (!whereIf) {
			map.put(beforeByPosition, "N");

		} else {
			bracketList.addAll(Arrays.asList(sqlArray));
			bracketList.add(wherePosition + 1, "(");
			// 因为上文增加了(，数组总长度加了1
			bracketList.add(beforeByPosition + 1, ")");

		}
		if (map.size() == 0) {
			return sourceSql;
		}
		NavigableMap<Integer, String> nmap = map.descendingMap();
		List<String> list = new ArrayList<>();
		if (0 < bracketList.size()) {
			list.addAll(bracketList);
		} else {
			list.addAll(Arrays.asList(sqlArray));
		}
		for (int i : nmap.keySet()) {
			if (i + 1 <= list.size()) {
				list.add(i, SqlHelper.createWhere(params, nmap.get(i)));
			} else {
				list.add(SqlHelper.createWhere(params, nmap.get(i)));
			}
		}
		return SqlHelper.toSql(list);
	}

	public String dealTempTables(String multiSql,
			Map<String, String> tempTableMap) {
		for (Map.Entry<String, String> tempTable : tempTableMap.entrySet()) {
			multiSql = multiSql.replace(tempTable.getKey(),
					tempTable.getValue());
		}
		if (multiSql.contains("tempT_able_")) {
			multiSql = dealTempTables(multiSql, tempTableMap);
		}
		return multiSql;
	}

	public String dealBracket(String sourceSql, Map<String, Object> params,
			List<String> ignoreTables) {
		String[] sqlArray = StringUtils.split(sourceSql, " ");
		int length = sqlArray.length;
		// 处理内嵌的select
		Stack<String> subSqlStack = new Stack<String>();
		Map<String, String> tempTableMap = new HashMap<String, String>();
		for (int i = 0; i < length; i++) {

			if (subSqlStack.isEmpty()) {
				// 如果栈是空的
				subSqlStack.push(sqlArray[i]);
			} else if (sqlArray[i].equals(")")) {
				// 说明此时栈里已有左括号，开始出栈
				int stackSize = subSqlStack.size();
				String rightBracket = ")";
				String subPartSql = "";
				for (int j = 0; j < stackSize; j++) {
					if (subSqlStack.peek().equals("(")) {
						subPartSql = subSqlStack.pop()
								+ preParse(subPartSql, params, ignoreTables)
								+ " " + rightBracket;
						break;
					} else {
						subPartSql = subSqlStack.pop() + " " + subPartSql;
					}
				}

				String tempTableKey = "tempT_able_" + i;
				tempTableMap.put(tempTableKey, subPartSql);
				subSqlStack.push(tempTableKey);

			} else {

				subSqlStack.push(sqlArray[i]);
			}
		}
		// 处理嵌套完后，做一次总体处理
		String wholeSql = "";
		int stackLength = subSqlStack.size();
		for (int j = 0; j < stackLength; j++) {
			wholeSql = subSqlStack.pop() + " " + wholeSql;
		}
		String multiSql = preParse(wholeSql, params, ignoreTables);

		multiSql = dealTempTables(multiSql, tempTableMap);

		return multiSql;
	}

}
