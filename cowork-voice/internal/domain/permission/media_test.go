package permission

import "testing"

func TestMediaPermissions(t *testing.T) {
	tests := []struct {
		name                          string
		known, speak, video, liveRoom bool
		want                          MediaAccess
	}{
		{"voice listener retains data", true, false, false, false, MediaAccess{Subscribe: true, PublishData: true}},
		{"voice speaking does not grant video", true, true, false, false, MediaAccess{Subscribe: true, PublishData: true, Microphone: true}},
		{"voice video grants camera and screen together", true, false, true, false, MediaAccess{Subscribe: true, PublishData: true, Camera: true, ScreenShare: true}},
		{"live viewer remains subscribe-only", true, false, false, true, MediaAccess{Subscribe: true}},
		{"live host excludes camera and data", true, true, true, true, MediaAccess{Subscribe: true, Microphone: true, ScreenShare: true}},
		{"unknown voice access disables all media and data", false, true, true, false, MediaAccess{}},
		{"unknown live access disables all media and data", false, true, true, true, MediaAccess{}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := MediaAccessFor(tt.known, tt.speak, tt.video, tt.liveRoom); got != tt.want {
				t.Fatalf("media access = %+v, want %+v", got, tt.want)
			}
		})
	}
}
