package games.strategy.engine.random;

import java.io.IOException;
import java.util.StringTokenizer;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.PostMethod;

public class IronyRemoteDiceServer implements IRemoteDiceServer
{
    
    
    
    
    public String getName()
    {
        return "www.irony.com";
    }
    
    public String toString()
    {
        return getName();
    }

    
    public String postRequest(String player1, String player2, int max, int numDice, String text, String gameID) throws IOException
    {
        if(gameID.trim().length() == 0)
            gameID = "TripleA";
        String message = gameID + ":" + text;
        
        
        if (message.length() > 99)
            message = message.substring(0, 98);
                
        PostMethod post = new PostMethod("/cgi-bin/droll-query");
        NameValuePair[] data = {
          new NameValuePair("numdice", "" + numDice),
          new NameValuePair("numsides", "" + max),
          new NameValuePair("modroll", "No"),
          new NameValuePair("numroll", "" + 1),
          new NameValuePair("subject", message),
          new NameValuePair("roller", player1),
          new NameValuePair("gm", player2),
          new NameValuePair("send", "true"),          
        };
        post.setRequestHeader("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)");
        
        post.setRequestBody(data);
       
        HttpClient client = new HttpClient();
        try
        {
            client.getHostConfiguration().setHost("24.97.85.139");
            client.executeMethod(post);
            
            String result = post.getResponseBodyAsString();
            System.out.println(post.getStatusCode());
            return result;
        }
        finally
        {
            post.releaseConnection();
        }
    }

    
   
    /**
     * 
     * @throws IOException
     *             if there was an error parsing the string
     */
    public int[] getDice(String string, int count) throws IOException
    {
        String rollStartString;
        String rollEndString;
        if (count == 1)
        {
            rollStartString = "Roll 1: <b>";
            rollEndString = "</b>";
        } else
        {
            rollStartString = "<p>Roll 1:";
            rollEndString = "=";
        }

        int startIndex = string.indexOf(rollStartString);
        if (startIndex == -1)
        {
            throw new IOException("Cound not find start index, text returned is:" + string);

        }
        startIndex += rollStartString.length();

        int endIndex = string.indexOf(rollEndString, startIndex);
        if (endIndex == -1)
        {
            throw new IOException("Cound not find end index");
        }

        StringTokenizer tokenizer = new StringTokenizer(string.substring(startIndex, endIndex), " ,", false);

        int[] rVal = new int[count];
        for (int i = 0; i < count; i++)
        {
            try
            {
                //-1 since we are 0 based
                rVal[i] = Integer.parseInt(tokenizer.nextToken()) - 1;
            } catch (NumberFormatException ex)
            {
                ex.printStackTrace();
                throw new IOException(ex.getMessage());
            }
        }

        return rVal;
    }
    
    
}