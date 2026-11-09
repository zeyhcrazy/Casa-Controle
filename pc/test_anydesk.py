"""AnyDesk launcher tests. No real UI, passwords, shutdown or remote session."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import test_agente
from anydesk_control import AnyDeskControl


class AnyDeskTests(unittest.TestCase):
    def test_fixed_launch_read_id_and_rapid_clicks(self):
        path=Path('C:/Users/test/Downloads/AnyDesk.exe')
        with patch('anydesk_control.find_anydesk',return_value=path), patch('anydesk_control.subprocess.Popen') as launch, patch('anydesk_control.subprocess.run',return_value=subprocess.CompletedProcess([],0,b'123456789\r\n')) as query:
            c=AnyDeskControl();a=c.open();b=c.open()
            self.assertTrue(a['opened']);self.assertEqual(a['id'],'123456789');self.assertEqual(a,b)
            launch.assert_called_once();self.assertEqual(launch.call_args.args[0],[str(path)])
            self.assertIs(launch.call_args.kwargs['shell'],False)
            self.assertEqual(query.call_args.args[0],[str(path),'--get-id'])

    def test_dry_run_and_untrusted_id(self):
        with patch('anydesk_control.subprocess.Popen') as launch,patch('anydesk_control.find_anydesk') as find:
            self.assertFalse(AnyDeskControl(True).open()['opened']);launch.assert_not_called();find.assert_not_called()
        for output in [b'anydesk://register-license?key=anything',b'not-an-id',b'123\r\n456']:
            with patch('anydesk_control.subprocess.run',return_value=subprocess.CompletedProcess([],0,output)):
                self.assertEqual(AnyDeskControl.get_id(Path('AnyDesk.exe')),'')
        with patch('anydesk_control.subprocess.run',side_effect=subprocess.TimeoutExpired('AnyDesk',2)):
            self.assertEqual(AnyDeskControl.get_id(Path('AnyDesk.exe')),'')


class AnyDeskHTTPS(test_agente.AgentTest):
    def test_anydesk_auth_and_no_arbitrary_commands(self):
        with patch.object(self.state.anydesk,'open',return_value={'opened':True,'message':'OK'}) as launch:
            self.assertEqual(self.request('POST','/api/anydesk/open',{})[0],401)
            _,token=self.pair()
            self.assertEqual(self.request('POST','/api/anydesk/open',{},'wrong')[0],401)
            self.assertEqual(self.request('POST','/api/anydesk/open',{},token,origin='https://example.com')[0],403)
            self.assertEqual(self.request('POST','/api/anydesk/open',{'path':'anything'},token)[0],400)
            self.assertEqual(self.request('GET','/api/anydesk/open',token=token)[0],404)
            launch.assert_not_called()
            self.assertTrue(self.request('POST','/api/anydesk/open',{},token)[1]['opened']);launch.assert_called_once()
            self.assertFalse(self.request('GET','/api/status',token=token)[1]['remote_desktop'])

    def test_old_remote_endpoints_removed(self):
        _,token=self.pair()
        self.assertFalse(hasattr(self.state,'remote'))
        for path in ['/api/remote/start','/api/remote/input','/api/remote/stop']:
            self.assertEqual(self.request('POST',path,{},token)[0],404)
        self.assertEqual(self.request('GET','/api/remote/stream',token=token)[0],404)

    def test_missing_anydesk_error(self):
        _,token=self.pair()
        with patch.object(self.state.anydesk,'open',side_effect=FileNotFoundError('Missing')):
            self.assertEqual(self.request('POST','/api/anydesk/open',{},token)[0],409)


if __name__=='__main__':unittest.main()
