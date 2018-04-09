/*
 * TODO: Accept token from user account on site
 * TODO: Connect to smtp server and send log
 */

package com.vercr.savior_backup;

import com.dropbox.core.DbxAppInfo;
import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxWebAuth;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.CommitInfo;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.UploadSessionCursor;
import com.dropbox.core.v2.files.WriteMode;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;  
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport; 
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import org.apache.commons.lang3.SerializationUtils;
//import javax.swing.JOptionPane;

public class Program {
    
    private static final long CHUNK_SIZE= 8L << 20;
    String m_username;
    String dropboxdestination = "";
    String m_scrambledPass;
    long m_timebetweenupdates = 86400000;
    List<File> fileitems ;
    String Log;
    private String m_ACCESS_TOKEN = "";
    boolean m_erroroccured;
    boolean m_repeat = false;
    String m_mailaddress = "";
    HashMap<String,byte[]> hashes = new HashMap<String,byte[]>();
    
    public void Main() throws InterruptedException
    {
        //Todo: intialize
        Sleeploop();
    }
    
    public void Sleeploop() throws InterruptedException
    {
        try
        {
           do {
                
                m_erroroccured = false;
                ComposeLog();
                fileitems = new ArrayList<>();
                ReadIniFile();
                
                if(m_ACCESS_TOKEN.equals(""))
                    AuthUser();
                ReadHashes();
                
                DbxRequestConfig config = new DbxRequestConfig("Savior_backup", "en_GB");
                DbxClientV2 client = new DbxClientV2(config, m_ACCESS_TOKEN);
                try
                {
                    Log += "Using account under the name of "+client.users().getCurrentAccount().getName()+System.lineSeparator();
                }
                catch (DbxException e)
                {
                    m_erroroccured = true;
                    Log += "Could not connect to DropBox (is your token correct?)"+ System.lineSeparator();
                       Log += e.getLocalizedMessage()  + System.lineSeparator();
                }
                //FullAccount account = client.users().getCurrentAccount();
                //System.out.println(account.getName().getDisplayName());
		
                for(File uploadFile : fileitems)
                {
                    byte[] storedhash = null;
                    storedhash=hashes.get(uploadFile.getAbsolutePath());
                    MessageDigest md = MessageDigest.getInstance("MD5");
                    long size = uploadFile.length();
                    long uploaded = 0L;
                    if(size> CHUNK_SIZE*2)
                    {
                        String sessionId = null;
                        for(int i = 0; i < 5;++i) //max 5 upload attempts
                        {
                            try (InputStream in = new FileInputStream(uploadFile);
                                    DigestInputStream dis = new DigestInputStream(in, md))
                            {
                                if (CompareHash(dis,storedhash,md,uploadFile.getAbsolutePath()))
                                {
                                    Log += "File "+ uploadFile.getPath()+" skipped, hash is equal" +System.lineSeparator();
                                }
                                else
                                {
                                    String destination = "";
                                    destination = dropboxdestination+BuildDestination(uploadFile.getPath());

                                    in.skip(uploaded);
                                    //Start
                                    if(sessionId == null)
                                    {
                                        sessionId = client.files().uploadSessionStart()
                                                                    .uploadAndFinish(in,CHUNK_SIZE)
                                                                    .getSessionId();
                                        uploaded += CHUNK_SIZE;
                                    }

                                    UploadSessionCursor cursor = new UploadSessionCursor(sessionId, uploaded);
                                    //Append
                                    while((size-uploaded)> CHUNK_SIZE)
                                    {
                                        client.files().uploadSessionAppendV2(cursor)
                                                            .uploadAndFinish(in, CHUNK_SIZE);
                                        uploaded += CHUNK_SIZE;
                                        cursor = new UploadSessionCursor(sessionId, uploaded);
                                    }
                                    //Finish
                                    long remaining = size - uploaded;
                                    CommitInfo commitinfo = CommitInfo.newBuilder(destination)
                                                .withMode(WriteMode.OVERWRITE)
                                                .withMute(Boolean.TRUE)
                                                .build();
                                    FileMetadata metadata = client.files().uploadSessionFinish(cursor, commitinfo)
                                                                               .uploadAndFinish(in, remaining);
                                    return;
                                }
                                in.close();
                                dis.close();
                            }
                            catch (Exception e)
                            {
                               m_erroroccured = true;
                               Log += "Error uploading File "+uploadFile.getPath()+ " to destination : "+ dropboxdestination+BuildDestination(uploadFile.getPath())  + System.lineSeparator();
                               Log += e.getLocalizedMessage()  + System.lineSeparator();
                            }
                        }
                        Log += "Succes uploading File "+uploadFile.getPath() + System.lineSeparator();
                    }
                    else
                    {
                        try (InputStream in = new FileInputStream(uploadFile);
                                    DigestInputStream dis = new DigestInputStream(in, md))
                        {
                            if (CompareHash(dis,storedhash,md,uploadFile.getAbsolutePath()))
                            {
                                Log += "File "+ uploadFile.getPath()+" skipped, hash is equal" +System.lineSeparator();
                            }
                            else
                            {                     
                                String destination = "";
                                destination = dropboxdestination+BuildDestination(uploadFile.getPath());
                                FileMetadata metadata = client.files().uploadBuilder(destination)
                                                .withMode(WriteMode.OVERWRITE)
                                                .withMute(Boolean.TRUE)
                                                .uploadAndFinish(in);
                                Log += "Uploaded file: "+uploadFile.getPath()+System.lineSeparator();
                            }
                            in.close();
                            dis.close();
                        }
                        catch (Exception e)
                        {
                            m_erroroccured = true;
                           Log += "Error uploading File "+uploadFile.getPath()+ " to destination : "+ dropboxdestination+BuildDestination(uploadFile.getPath())  + System.lineSeparator();
                           Log += e.getLocalizedMessage()  + System.lineSeparator();
                        }	
                    }
                    StoreHashes();
                }
                SendMail();
            } while(m_repeat);
        }
        catch(Exception e)
        {
                    m_erroroccured = true;
            Log += "Connection interrupted while uploading" + System.lineSeparator();
            Log += e.getLocalizedMessage() + System.lineSeparator();
        }
    }
    public void ReadIniFile()
    {
        String filename = "settings.ini";
        String line;
        String[] setting;
        try
        {
            String temp = Main.class.getProtectionDomain().getCodeSource().getLocation().getPath() ;
            if (temp.endsWith(".jar"))
            {
                File jar =new File( Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
                temp = jar.getParent();
            }
            Log += "Reading settings in location: "+temp+"/"+  filename +System.lineSeparator();
            //JOptionPane.showMessageDialog(null,                               "filepath: "+temp,                               "TITLE",                               JOptionPane.WARNING_MESSAGE);
            FileReader filereader = new FileReader( new File(temp)+"/"+  filename);
            //FileReader filereader = new FileReader( new File("c:\\test\\settings.ini"));
            try (BufferedReader buf = new BufferedReader(filereader)) 
            {
                while((line = buf.readLine()) != null)
                {
                    setting = line.split("=");
                    String settingvar = setting[0];
                    
                    switch (settingvar) {
                        case "username":
                            m_username = setting[1];
                            break;
                        case "password":
                            m_scrambledPass = setting[1];
                            break;
                        case "updaterate":
                            m_timebetweenupdates = Long.parseLong(setting[1]);
                            break;
                        case "folder":
                            File newfolder = new File(setting[1]);
                            if (newfolder.isDirectory()) 
                            {
                                listFilesForFolder(newfolder);
                            } 
                            else 
                            {
                                fileitems.add(newfolder);
                            }
                            break;
                        case "token":
                            m_ACCESS_TOKEN =  setting[1];
                            break;
                        case "dropboxdestination":
                            dropboxdestination =  setting[1];
                            break;
                        case "autorepeat":
                            if(setting[1].equals("on"))
                                m_repeat = true;
                            else
                                m_repeat = false;
                            break;
                        case "mailaddress":
                            m_mailaddress = setting[1]; 
                            break;
                        default:
                            Log += "settings.ini not in the correct format. Check installation instructions" + System.lineSeparator();
                    }
                }
                buf.close();
            }
            filereader.close();
        }
        catch(Exception e)
        {
            m_erroroccured = true;
            Log += "Error loading settings.ini. Is it in the correct location?" + System.lineSeparator();
            Log += e.getLocalizedMessage()  + System.lineSeparator();
        }
    } 
   private void listFilesForFolder(final File folder) //found in settings.ini
{
	for (final File fileEntry : folder.listFiles()) {
        if (fileEntry.isDirectory()) 
        {
            listFilesForFolder(fileEntry);
        } 
        else 
        {
            fileitems.add(fileEntry);
        }
    }
}

    private void ComposeLog() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
       Log = "New log started: "+dateFormat.format(date) + System.lineSeparator();
    }
    
public void SendMail()
{
  
  String host="smtp.office365.com";  
  final String user=""; // fill in you smtp username here
  final String password=""; //smtp password
    
  //String to="info@core-computing.be";
  String to=m_mailaddress;
  //String to="gertvercruyssen@gmail.com";
  
   //Get the session object  
   Properties props = new Properties();  
   props.put("mail.smtp.host",host);  
   props.put("mail.smtp.auth", "true");  
   props.put("mail.smtp.port", "587");
		props.put("mail.smtp.starttls.enable", "true");
     
   Session session = Session.getDefaultInstance(props,  
    new Authenticator() {  
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {  
    return new PasswordAuthentication(user,password);  
      }  
    });  
  
   //Compose the message  
    try {  
     MimeMessage message = new MimeMessage(session);  
     message.setFrom(new InternetAddress(user));  
     message.addRecipient(Message.RecipientType.TO,new InternetAddress(to));  
     String title = "Backup logs " + m_username;
     if(m_erroroccured)
         title+=" FAILED";
     else
         title+=" Success";
     message.setSubject(title);  

     Log += "Mail composed succesfully" + System.lineSeparator();
     if(m_repeat)
        Log += "Finished, sleeping for "+ m_timebetweenupdates+" milliseconds" + System.lineSeparator();
     else
        Log += "Finished, thank you and come again!" + System.lineSeparator();
   
     message.setText(Log);  
       
    //send the message  
     Transport.send(message);  
  
     } 
    catch (Exception e) 
	 {
                    m_erroroccured = true;
	 	Log += "Error Sending Mail" +e.getLocalizedMessage() + System.lineSeparator();
                
                       Log += e.getLocalizedMessage()  + System.lineSeparator();
	 }  
 }  

    private String BuildDestination(String path) {
        String destination ="";
        
        path = path.replaceAll(":", "");
        path = path.replaceAll("\\\\", "/");
        if(path.startsWith("/"))
            destination += "/"+m_username + path;
        else    
            destination += "/"+m_username+"/" + path;
        
        return destination;
    }

    private boolean AuthUser() throws IOException
    {
        System.out.println("No access token, please log into dropbox to authorize user");
        DbxAppInfo info = new DbxAppInfo("", ""); // keys provided by dropbox go here
        DbxRequestConfig requestconfig = new DbxRequestConfig("Savior_backup");
        DbxWebAuth webauth = new DbxWebAuth(requestconfig,info);
        DbxWebAuth.Request webauthrequest = DbxWebAuth.newRequestBuilder().withNoRedirect().build();
        
        String authurl = webauth.authorize(webauthrequest);
        System.out.println("1. Please go to "+authurl);
        System.out.println("2. Log in if needed with your dropbox account");
        System.out.println("3. Copy the authorization code here:");
        
        String code = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if(code == null)
            return false;
        code = code.trim();
        
        DbxAuthFinish finish;
        try
        {
            finish = webauth.finishFromCode(code);
        }
        catch(DbxException e)
        {
            Log += "Error Authenticating user" + System.lineSeparator();
            Log += e.getLocalizedMessage() + System.lineSeparator();
            return false;
        }
        System.out.println("Authorization complete");
        m_ACCESS_TOKEN = finish.getAccessToken();
        SaveAccessToken();
        
        System.out.println("Token saved in settings");
        
        if (m_ACCESS_TOKEN.equals(""))
            return false;
        else
            return true;
    }

    private void SaveAccessToken()
    {
        String filename = "settings.ini";
        String line;
        String Text = "";
        String path;
        try
        {
            path = Main.class.getProtectionDomain().getCodeSource().getLocation().getPath() ;
            if (path.endsWith(".jar"))
            {
                File jar =new File( Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
                path = jar.getParent();
            }
            Log += "Reading caches in location: "+path+"/"+  filename +System.lineSeparator();
            FileReader filereader = new FileReader( new File(path)+"/"+  filename);
            try (BufferedReader buf = new BufferedReader(filereader)) 
            {
                while((line = buf.readLine()) != null)
                {
                    Text +=line+System.lineSeparator();
                }
            }
            Text += "token="+m_ACCESS_TOKEN+System.lineSeparator();
            filereader.close();
            FileWriter writer = new FileWriter( new File(path)+"/"+  filename);
            writer.append(Text); 
            writer.close();
        }
        catch(Exception e)
        {
            m_erroroccured = true;
            Log += "Could not save to settings" + System.lineSeparator();
            Log += e.getLocalizedMessage() + System.lineSeparator();
        }
    }

    private boolean CompareHash(DigestInputStream in, byte[] storedhash, MessageDigest md, String filepath) throws IOException 
    {
        int size = 102400;
        byte[] readdata = new byte[size];
        int gotmore = 1;
        try 
        {
            while(gotmore > 0)
            {
                gotmore = in.read(readdata);
            }
        }
        catch(Exception e)
        {
            m_erroroccured = true;
            Log += "Error creating hash of file" + System.lineSeparator();
            Log += e.getLocalizedMessage() + System.lineSeparator();
        }
        byte[] digest = md.digest();
        hashes.put(filepath, digest);
        if (Arrays.equals(digest, storedhash))
                return true;
        return false;
    }

    private void ReadHashes()
    {
        String filename = "cache.ix";
        String path;
        byte[] finalbytes = new byte[0];
        byte[] readbuffer = new byte[1024];
        try
        {
            path = Main.class.getProtectionDomain().getCodeSource().getLocation().getPath() ;
            if (path.endsWith(".jar"))
            {
                File jar =new File( Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
                path = jar.getParent();
            }
            Log += "Reading settings in location: "+path+"/"+  filename +System.lineSeparator();
            FileInputStream stream = new FileInputStream( new File(path)+"/"+  filename);
            while(stream.read(readbuffer) != -1)
            {
                 finalbytes = AddByteArrays(finalbytes,readbuffer);
            }
            stream.close();
            hashes = (HashMap<String,byte[]>) SerializationUtils.deserialize(finalbytes);
        }
        catch(Exception e)
        {
            m_erroroccured = true;
            Log += "Could not read hashes" + System.lineSeparator();
            Log += e.getLocalizedMessage() + System.lineSeparator();
        }
    }

    private void StoreHashes()
    {
        String filename = "cache.ix";
        String path;
        FileOutputStream writer = null;
        try
        {
            path = Main.class.getProtectionDomain().getCodeSource().getLocation().getPath() ;
            if (path.endsWith(".jar"))
            {
                File jar =new File( Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
                path = jar.getParent();
            }
            //Log += "writing hashes in location: "+path+"/"+  filename +System.lineSeparator();            
            try
            {
                writer = new FileOutputStream( new File(path)+"/"+  filename,false);
                writer.write(SerializationUtils.serialize(hashes));
            }
            catch(IOException e)
            {
                Log+= "Could not save hashes" + System.lineSeparator();
                Log += e.getLocalizedMessage() + System.lineSeparator();                
            }
            finally
            {
                if(writer != null)
                    writer.close();
            }
        }
        catch(Exception e)
        {
            m_erroroccured = true;
            Log += "Could not save hashes" + System.lineSeparator();
            Log += e.getLocalizedMessage() + System.lineSeparator();
        }
    }

    private byte[] AddByteArrays(byte[] one, byte[] two)
    {
        byte[] combined = new byte[one.length + two.length];
        for (int i = 0; i < combined.length; ++i)
        {
            combined[i] = i < one.length ? one[i] : two[i - one.length];
        }
        return combined;
    }
}