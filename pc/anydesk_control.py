"""Opens only the local AnyDesk executable. No capture, input injection or shell."""
from __future__ import annotations
import os
from pathlib import Path
import re
import subprocess
import threading
import time


def find_anydesk():
    home=Path.home()
    candidates=[
        Path(os.environ.get('ProgramFiles(x86)','C:/Program Files (x86)'))/'AnyDesk/AnyDesk.exe',
        Path(os.environ.get('ProgramFiles','C:/Program Files'))/'AnyDesk/AnyDesk.exe',
        Path(os.environ.get('LOCALAPPDATA',str(home/'AppData/Local')))/'Programs/AnyDesk/AnyDesk.exe',
        Path(os.environ.get('LOCALAPPDATA',str(home/'AppData/Local')))/'AnyDesk/AnyDesk.exe',
        home/'Downloads/AnyDesk.exe',home/'Desktop/AnyDesk.exe',
    ]
    for path in candidates:
        if path.is_file():return path.resolve()
    raise FileNotFoundError('AnyDesk nao encontrado no PC. Coloque AnyDesk.exe em Downloads ou instale-o normalmente.')


class AnyDeskControl:
    def __init__(self,dry_run=False):
        self.dry_run=dry_run
        self.lock=threading.Lock()
        self.last_open=0.0
        self.last_id=''

    @staticmethod
    def get_id(path):
        try:
            result=subprocess.run([str(path),'--get-id'],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                                  timeout=2,check=False,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            value=result.stdout.decode('utf-8-sig',errors='replace').strip()
            return value if result.returncode==0 and re.fullmatch(r'[0-9]{6,16}',value) else ''
        except (OSError,subprocess.TimeoutExpired):return ''

    def open(self):
        if self.dry_run:return {'opened':False,'message':'Modo de teste: AnyDesk nao foi aberto.','id':''}
        with self.lock:
            path=find_anydesk()
            now=time.monotonic()
            if now-self.last_open>=1.0:
                # Fixed executable, no arguments from the network and shell=False.
                # Opening its GUI is the explicit action requested by the user.
                subprocess.Popen([str(path)],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
                                 shell=False,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                self.last_open=now
                self.last_id=self.get_id(path)
                if not self.last_id:
                    time.sleep(.25)
                    self.last_id=self.get_id(path)
            return {'opened':True,'message':'AnyDesk aberto no PC. A conexao usa a autorizacao configurada no AnyDesk.','id':self.last_id}
