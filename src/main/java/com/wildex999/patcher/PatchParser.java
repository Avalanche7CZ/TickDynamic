package com.wildex999.patcher;

import java.util.*;
import java.util.regex.*;

public class PatchParser {
    public enum TokenType {
        Anything,
		AnythingLine,
		AnythingLess,
		AnythingMore,
		AnythingExact,
		AnythingSet,
		AnythingGet,
		RegEx,
		TouchVar,
		Text,
		CommandOrigin,
		CommandLimit,
		CommandReset,
		CommandUnlimit,
		CommandStart,
		CommandEnd,
		CommandAdd,
		CommandSubtract
	}
	
	public static class CommandToken {
		public TokenType type; //The type of token
		public String text; //Any text bound to this command
		public List<ReplacementToken> replacementTokens = new ArrayList<ReplacementToken>(); //Tokens where text will be replaced, and also tokens for raw text
		public static int tokenCounter = 0;
		public int tokenId;
		
		public CommandToken(TokenType type) { this.type = type; tokenId = tokenCounter++;}
		public CommandToken(TokenType type, String text) { this.type = type; this.text = text; tokenId = tokenCounter++;}
	}
	
	public static class ReplacementToken {
		public TokenType type;
		public Object var; //Variable(A String or an int depending on type)
		
		public ReplacementToken(TokenType type) { this.type = type; }
		public ReplacementToken(TokenType type, Object var) { this.type = type; this.var = var; }
	}
	
	List<CommandToken> tokens; //List of command tokens in execution order
	Map<String, String> variableMap;
	
	//Parse the patch, throws an exception on failure
	public void parsePatch(String patch) throws Exception {
		tokens = new ArrayList<CommandToken>();
		
		patch = patch.replace("\r", ""); //normalize newline
		
		//First we parse the commands, as we need both the start and the end
		boolean inMultiLineComment = false;
		TokenType currentCommand = null;
		int commandContentIndex = 0;
		System.out.println("Patch length: " + patch.length());
		for(int i=0; i<patch.length(); i++) {			
			if(inMultiLineComment && patch.charAt(i) != '!')
				continue;
			
			switch(patch.charAt(i))
			{
			case '@': //Command
				TokenType command;
				char currentChar = patch.charAt(++i);
				
				switch(currentChar)
				{
				case 'O':
					command = TokenType.CommandOrigin;
					break;
				case 'L':
					command = TokenType.CommandLimit;
					break;
				case 'R':
					command = TokenType.CommandReset;
					break;
				case 'U':
					command = TokenType.CommandUnlimit;
					break;
				case 'S':
					command = TokenType.CommandStart;
					break;
				case 'E':
					command = TokenType.CommandEnd;
					break;
				case '+':
					command = TokenType.CommandAdd;
					break;
				case '-':
					command = TokenType.CommandSubtract;
					break;
				case '|':
					command = null;
					break;
				default:
					throw new Exception("("+i+")Unknown command: " + currentChar);
				}
				
				if(currentCommand != null) {
					tokens.add(new CommandToken(currentCommand, patch.substring(commandContentIndex, i-1)));
					System.out.println("Wrote command: " + currentCommand + " with Text: " + tokens.get(tokens.size()-1).text);
				}
				
				if(command != null) {
					currentCommand = command;
					commandContentIndex = i+1;
				}
				else
					currentCommand = null;
				break;
			case '%':
				i++; //Bypass next char
				break;
			case '"':
				if(currentCommand != null)
					break;
				i = patch.indexOf('\n', i);
				if(i == -1)
					i=patch.length(); //Reached the end
				break;
			case '!':
				if(currentCommand != null)
					break;
				inMultiLineComment = !inMultiLineComment;
				break;
			default:
					
				break;
			}
		}
		
		System.out.println("Done parsing step 1");
		
		//Parse the replacement tokens inside each command
		for(Iterator<CommandToken> it = tokens.iterator(); it.hasNext(); )
		{
			CommandToken token = it.next();
			StringBuilder str = new StringBuilder(token.text);
			
			int startIndex = 0; //Used for creating text tokens
			int index;
			for(int i=0; i<str.length(); i++) {
				char currentChar = str.charAt(i);
				TokenType type;
				
				switch(currentChar) 
				{
				case '*':
					token.replacementTokens.add( new ReplacementToken(TokenType.Text, str.substring(startIndex, i)) );
					token.replacementTokens.add(new ReplacementToken(TokenType.Anything));
					startIndex = i + 1;
					break;
				case '+':
					token.replacementTokens.add( new ReplacementToken(TokenType.Text, str.substring(startIndex, i)) );
					token.replacementTokens.add(new ReplacementToken(TokenType.AnythingLine));
					startIndex = i + 1;
					break;
				case '<':
				case '>':
				case '#':
				case '^':
					//Add a token with the text before our replacement
					token.replacementTokens.add( new ReplacementToken(TokenType.Text, str.substring(startIndex, i)) );
					index = str.indexOf("]", i);
					//Add new token with the contained value for length
					if(currentChar == '<')
						type = TokenType.AnythingLess;
					else if(currentChar == '>')
						type = TokenType.AnythingMore;
					else if(currentChar == '#')
						type = TokenType.AnythingExact;
					else
						type = TokenType.TouchVar;
					token.replacementTokens.add( new ReplacementToken(type, Integer.parseInt(str.substring(i+1, index))) );
					startIndex = index + 1;
					i = index;
					
					break;
				case '?':
				case '=':
					token.replacementTokens.add( new ReplacementToken(TokenType.Text, str.substring(startIndex, i)) );
					index = str.indexOf("]", i);
					if(currentChar == '?')
						type = TokenType.AnythingSet;
					else
						type = TokenType.AnythingGet;
					token.replacementTokens.add( new ReplacementToken(type, str.substring(i+1, index)) );
					startIndex = index + 1;
					i = index;
					break;
				case '{':
					token.replacementTokens.add( new ReplacementToken(TokenType.Text, str.substring(startIndex, i)) );
					index = str.indexOf("]", i);
					String[] var = new String[2];
					var[0] = str.substring(i+1, index); //The varname
					//Find the end of the RegEx
					int regStart = index;
					while(true) {
						index = str.indexOf("}", index);
						if(str.charAt(index-1) != '%')
							break;
						str.deleteCharAt(index-1);
					}
					var[1] = str.substring(regStart, index); //The RegEx
					token.replacementTokens.add( new ReplacementToken(TokenType.RegEx, var) );
					
					startIndex = index + 1;
					i = index;
					break;
				case '%':
					str.deleteCharAt(i);
					break;
				}
				
			}
			
			//Add a text token for any remaining text after last replacement
			if(startIndex < str.length())
				token.replacementTokens.add(new ReplacementToken(TokenType.Text, str.substring(startIndex)) );

			System.out.println("Replacements for " + token.type + ": ");
			for(Iterator<ReplacementToken> ita = token.replacementTokens.iterator(); ita.hasNext(); )
			{
				ReplacementToken t = ita.next();
				System.out.println("- " + t.type + " :: " + t.var);
			}
			
		}
	}
	
	//Patch the given base using the parsed patch
	public String patch(String basee) throws Exception {
		basee = basee.replace("\r", ""); //normalize newline
		StringBuilder output = new StringBuilder(basee); //Our patched base
		variableMap = new HashMap<String, String>();
		Pattern pattern;
		Matcher matcher;
		int startIndex;
		
		int baseOrigin = 0;
		int baseLimit = basee.length();
		
		if(tokens == null)
			throw new Exception("parsePatch must be run before patch, can not patch without tokens!");
			
		for(int i = 0; i < tokens.size(); i++)
		{
			CommandToken token = tokens.get(i);
			
			if(token.type == TokenType.CommandOrigin)
			{
				StringBuilder regEx = new StringBuilder();
				Map<String, Boolean> varSet = new HashMap<String, Boolean>();
				generateReplacementRegEx(token, regEx, varSet);
				pattern = Pattern.compile(regEx.toString());
				matcher = pattern.matcher(output);
				if(!matcher.find())
					throw new Exception("(Token:"+i+")Unable to match Origin: " + regEx.toString());
				baseOrigin = matcher.start();
				continue;
			}
			else if(token.type == TokenType.CommandLimit)
			{
				StringBuilder regEx = new StringBuilder();
				Map<String, Boolean> varSet = new HashMap<String, Boolean>();
				generateReplacementRegEx(token, regEx, varSet);
				pattern = Pattern.compile(regEx.toString());
				matcher = pattern.matcher(output);
				if(!matcher.find())
					throw new Exception("(Token:"+i+")Unable to match Limit: " + regEx.toString());
				baseLimit = matcher.start();
				continue;
			}
			else if(token.type == TokenType.CommandReset)
			{
				baseOrigin = 0;
				continue;
			}
			else if(token.type == TokenType.CommandUnlimit)
			{
				baseLimit = output.length();
				continue;
			}
			else if(token.type != TokenType.CommandStart)
				throw new Exception("(Token:"+i+")Expected CommandStart, but got: " + token.type + " with content: " + token.text);
			
			startIndex = i;
			
			//Generate the detection RegEx
			StringBuilder detectionRegEx = new StringBuilder(); 
			Map<String, Boolean> varSet = new HashMap<String, Boolean>();
			boolean ended = false;
			for(; i<tokens.size(); i++)
			{
				token = tokens.get(i);
				
				if(token.type == TokenType.CommandAdd)
					continue;
				else if(token.type == TokenType.CommandOrigin)
					new Exception("(Token:"+i+")Can not define an Origin between Start and End.");
				else if(token.type == TokenType.CommandLimit)
					new Exception("(Token:"+i+")Can not define an Limit between Start and End.");
				else if(token.type == TokenType.CommandEnd && token.text.length() == 0)
				{
					ended = true;
					break; //Ignore empty end
				}
				
				//Generate RegEx for replacement tokens
				if(token.type == TokenType.CommandStart)
					detectionRegEx.append("("); //Start number group for later position reference
				else
					detectionRegEx.append("(?<t" + token.tokenId + ">");
				generateReplacementRegEx(token, detectionRegEx, varSet);
				detectionRegEx.append(")");
				
				if(token.type == TokenType.CommandEnd)
				{
					ended = true;
					break;
				}
			}
			
			if(ended != true)
				throw new Exception("Reached end of stream without End command!");
			
			System.out.println("(" + token.type + ") RegEx: " + detectionRegEx.toString());
			
			//Find occurrence in base using generated RegEx
			String subBase = output.substring(baseOrigin, baseLimit);
			pattern = Pattern.compile(detectionRegEx.toString(), Pattern.DOTALL);
			matcher = pattern.matcher(subBase);
			if(!matcher.find())
				throw new Exception("Failed to match: '" + detectionRegEx.toString() + "'\nOn base: '" + subBase +"'");
			
			//Read back any set variables
			for(Iterator<String> it = varSet.keySet().iterator(); it.hasNext(); )
			{
				String varName = it.next();
				String varValue = matcher.group(varName);
				if(varValue == null)
					throw new Exception("Variable '" + varName + "' was marked as set, but was null!");
				variableMap.put(varName, varValue);
			}
			
			//Start applying the actions
			int offset = baseOrigin + matcher.end(1); //The first group is always the CommandStart group
			
			//First we do removal
			for(i = startIndex; i<tokens.size(); i++)
			{
				token = tokens.get(i);
				if(token.type == TokenType.CommandEnd)
					break;
				if(token.type != TokenType.CommandSubtract)
					continue;
				
				int length = matcher.group("t"+token.tokenId).length();
				output.delete(offset, offset+length);
				
				baseLimit -= length;
			}
			
			//Then do additions
			for(i = startIndex; i<tokens.size(); i++)
			{
				token = tokens.get(i);
				
				if(token.type == TokenType.CommandEnd)
					break;
				if(token.type != TokenType.CommandAdd)
					continue;
				
				for(Iterator<ReplacementToken> it = token.replacementTokens.iterator(); it.hasNext();)
				{
					ReplacementToken repToken = it.next();
					String in;
					if(repToken.type == TokenType.Text)
						in = (String)repToken.var;
					else if(repToken.type == TokenType.AnythingGet)
					{
						in = variableMap.get((String)repToken.var);
						if(in == null)
							throw new Exception("Tried to use nonexistent variable '" + (String)repToken.var + "' during Add command.");
					}
					else
						throw new Exception("Unsuported action during CommandAdd: " + repToken.type);
					
					output.insert(offset, in);
					baseLimit += in.length();
					offset += in.length();
				}
			}

		}
		
		return output.toString();
	}
	
	protected void generateReplacementRegEx(CommandToken token, StringBuilder detectionRegEx, Map<String, Boolean> varSet) throws Exception {
		for(int repToken = 0; repToken < token.replacementTokens.size(); repToken++)
		{
			ReplacementToken action = token.replacementTokens.get(repToken);
			
			if(action.type == TokenType.Text)
				detectionRegEx.append(Pattern.quote((String)action.var));
			else if(action.type == TokenType.Anything)
				detectionRegEx.append(".+"); //We use DOTALL so it includes newline
			else if(action.type == TokenType.AnythingLine)
				detectionRegEx.append("[^<>\\r\\n]+"); //Pretty much the .+ without DOTALL
			else if(action.type == TokenType.AnythingExact)
				detectionRegEx.append(".{").append(action.var).append("}");
			else if(action.type == TokenType.AnythingLess)
				detectionRegEx.append(".{0,").append(((Integer)action.var)+1).append("}");
			else if(action.type == TokenType.AnythingMore)
				detectionRegEx.append(".{").append(action.var).append(",}");
			else if(action.type == TokenType.AnythingSet)
			{
				if(varSet.get((String)action.var) != null)
					throw new Exception("(Token:"+token.type+") Trying to set a variable("+ action.var +") that has already been set!");
				detectionRegEx.append("(?<").append(action.var).append(">[^<>\\r\\n]+)");
				varSet.put((String)action.var, true);
			}
			else if(action.type == TokenType.AnythingGet)
			{
				if(varSet.get((String)action.var) == null)
				{
					if(variableMap.get((String)action.var) == null)
						throw new Exception("(Token:"+token.type+") Trying to get a variable(" + action.var + ") which has not yet been set!");
					//Been set during a previous Start/End, so we set it now
					detectionRegEx.append("(?<").append(action.var).append(">").append(variableMap.get((String)action.var)).append(")");
					varSet.put((String)action.var, true);
				}
				else //Been set during this Start/End, so we just reference it
					detectionRegEx.append("\\k<").append(action.var).append(">"); 
			}
			else if(action.type == TokenType.RegEx)
			{
				detectionRegEx.append("(?<").append( ((String[])action.var)[0] ).append(">").append( ((String[])action.var)[1] ).append(")");
				varSet.put(((String[])action.var)[0], true);
			}
			else if(action.type == TokenType.TouchVar)
				varSet.put((String) action.var, true);
			
		}
	}
}
