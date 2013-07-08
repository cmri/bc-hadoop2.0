#!/usr/bin/python   
#-*-coding:UTF-8 -*-   
import sys  
  
rack = {"compute-13-10.local":"rack-1",  
        "compute-13-12.local":"rack-1",  
        "compute-13-14.local":"rack-2",  
        "compute-13-16.local":"rack-2",   
        "192.168.32.92":"rack-1",  
        "192.168.32.94":"rack-1",  
        "192.168.32.96":"rack-2",  
        "192.168.32.98":"rack-2",  
        }  
  
  
if __name__=="__main__":  
    print "/" + rack.get(sys.argv[1],"rack-default")  
